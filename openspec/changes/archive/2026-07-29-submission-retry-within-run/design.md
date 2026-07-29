## Context

`LlmDecisionSubmissionGateway.submissionTask()`（`LlmDecisionSubmissionGateway.kt:184-217`）は次の構造を持つ。

```kotlin
try {
    runCatching {
        server.accept().use { channel ->
            val response = runCatching { readFrame(channel); handleRequest(...) }
                .onSuccess { submissionState.set(COMMITTED) }
                .getOrElse { gatewayErrorResponse(it) }
            writeFrame(channel, response)
        }
    }
} finally {
    completion.countDown()
}
```

`accept()` が 1 回、`readFrame` が 1 回で、応答を書いたら channel も task も終わる。client 側（`LlmDecisionSubmissionGatewayClient`）は MCP server の起動時に 1 度 connect し、そのチャネルを保持したまま `submitDecision` を呼ぶため、2 回目の `submitDecision` は閉じた channel への write / read になり `IOException` になる。

`close()` は `server.close()` → `executor.shutdownNow()` → `awaitTermination(500ms)` の順で、`awaitTermination` が false なら例外を投げる契約になっている（`:84-96`）。`awaitCompletion()` は `CountDownLatch(1).await()` で、canary（`McpIsolationCanaryArtifacts.kt:155`）が「1 request が終わるまでプロセスを生かす」ために使う唯一の呼び出し元である。

state 遷移は `submitRepositoryRequest` 冒頭で `IN_FLIGHT`、成功で `COMMITTED`、失敗で `compareAndSet(NOT_ATTEMPTED, REJECTED)`。CAS のため、repository へ到達した後の失敗（conflict / unknown）は `IN_FLIGHT` のまま残り、audit では `UNKNOWN` になる。この挙動は「commit されたかもしれない」を「されていない」に変換しないための意図的な設計で、維持する。

## Goals / Non-Goals

**Goals:**

- 拒否された提出のあと、同一 run 内で再提出が成立する
- 接続の張り直しでも張り替えなしでも再提出できる（client 実装の詳細に依存しない）
- 一度 `COMMITTED` になった状態を後続要求で劣化させない
- `close()` と `awaitCompletion()` の外部契約を変えない

**Non-Goals:**

- 並行 submission の受理。逐次処理を維持する（repository の authority が並行 winner を裁定する経路は既存テストが担保しており、gateway 側で並行度を増やす理由がない）
- retry 回数の上限や rate limit。tool call 上限が既に上流で効いている
- client 側の自動 reconnect。gateway が受け付けられれば client の既存 channel でそのまま再提出できる
- fail-closed 原則の変更。提出が成立しなければ NO_TRADE のまま

## Decisions

### D1: 単一 worker thread のまま二重ループにする

`submissionTask()` を「accept ループ」の内側に「frame ループ」を持つ構造へ変える。

```
while (!Thread.interrupted()) {
    val channel = server.accept()            // close で AsynchronousCloseException / ClosedChannelException
    channel.use {
        while (true) {
            val request = readFrame(channel)  // EOF なら break
            val response = process(request)
            writeFrame(channel, response)
        }
    }
}
```

executor は既存の single thread を維持するため、要求は逐次で処理される。`accept()` は `close()` 時に `AsynchronousCloseException` を投げ、`shutdownNow()` の interrupt と合わせてループが抜ける。

**代替案**: 接続ごとに thread を割く。却下 — 並行 submission を受け付けることになり、Non-Goals に反する。gateway の client は 1 プロセス 1 接続で、多重化の需要がない。

### D2: 正常な EOF と異常を区別する

現在の `readFully()` は `channel.read(buffer) >= 0` を `check` するため、client が切断すると `IllegalStateException("Submission gateway frame ended early.")` になる。ループ化するとこれが「正常な接続終了」と区別できないため、frame の先頭読み取りだけ EOF を許容する形へ分ける。

- size prefix の最初の read が `-1` を返す → その接続は正常終了、accept ループへ戻る
- size prefix の途中、または payload の途中で `-1` → 従来どおり `IllegalStateException`（ただし接続を閉じるだけで gateway は継続）
- frame size が許容範囲外 → public な `readFrame()` は従来どおり `IllegalArgumentException`。内部の `readFrameOrNull()` は接続を閉じるべき契約違反として専用例外を使ってよいが、既存 client から見た例外型は変えない

**なぜ分けるか**: 「要求を送らずに閉じた」を異常として扱うと、client の close だけで gateway が壊れる。Requirement「client の切断で gateway は終了しない」の直接の根拠。

### D3: 例外は接続スコープに閉じ込める

frame ループ内の例外は、応答を書ける状態なら `gatewayErrorResponse()` を書いて次の frame を待つ。書けない状態（write 自体の失敗、frame 契約違反）は接続を閉じて accept ループへ戻る。accept ループ自体は `server` が閉じられたときだけ抜ける。

これにより、1 つの壊れた接続が gateway 全体を落とさない。

### D4: `completion` は最初の応答送信成功時に countDown し、停止時にも countDown する

`awaitCompletion()` の意味論（「最初の 1 request の応答が client へ送信されるまで待つ」）は canary が依存しているため変えない。frame 処理が response を生成しても、`writeFrame` が失敗して client へ届かなければ request 完了とは扱わない。`writeFrame` が成功した場合だけ `completion.countDown()` を呼ぶ。`CountDownLatch.countDown()` は 2 回目以降が no-op なので、後続 frame の送信成功時にも呼んでよい。加えて task の `finally` でも countDown し、要求が 1 度も来ない場合や応答を書けずに停止した場合にも gateway 停止時は待機を解除する。

canary は `awaitCompletion()` の後 `use { }` を抜けて `close()` する。gateway が複数要求を受け付けられるようになっても、canary は client が 1 応答を受信した時点で閉じる。canary の目的は「gateway 経由で 1 提出が成立し、応答が client へ届く」ことの確認であり、複数提出の検証ではない。

### D4b: accept ループは例外種別を問わず抜ける

`close()` は `server.close()` を先に呼ぶ（`LlmDecisionSubmissionGateway.kt:84-96`）。closed な `ServerSocketChannel` への `accept()` は blocking せず即座に例外を投げるため、accept ループが例外を握り潰して継続する形だと busy loop になり、`awaitTermination(500ms)` を恒常的に踏んで `close()` が毎回 `IllegalStateException` を投げる。

規則を次のとおり固定する。

- `accept()` が投げた例外は種別を問わずループを抜ける（`ClosedChannelException` / `AsynchronousCloseException` / `IOException` を区別しない）
- 接続スコープに閉じ込めるのは frame ループ内の例外だけ
- ループ条件は `Thread.currentThread().isInterrupted` を読む。`Thread.interrupted()` は割り込みフラグを消費するため使わない

「close 後に worker thread が終了している」テスト（tasks 4.4）がこの規則を担保する。

### D4c: 再提出が成立した run の outcome 判定

`ToolCallGuard.runAndAudit` は tool 失敗を検知した時点で `NO_TRADE_EXIT` を書く（`ToolCallGuard.kt:174`）。single-shot の現在は初回拒否が run terminal なので「拒否イベントがある run」と「entry が成立した run」は排他だが、再提出が可能になるとこの前提が崩れる。

run outcome は `hasNoEntryEvidence()`（`DecisionRunProjectionRepository.kt:112-120`）が `action == NO_TRADE || hasNoTradeExit` を基礎に判定する。再提出対応では、commit 済み trade decision がある run の submission 拒否を superseded にする必要がある一方、同じ run に汎用 tool 失敗が混在する場合は no-entry 証跡を保持する必要がある。最新 `NO_TRADE_EXIT` 1 行だけから rejection の種類を判定すると、先行する汎用 tool 失敗の有無が後続イベントの順序で見えなくなる。

SQL projection は 2 つの独立した LATERAL に分ける。既存の LATERAL は `ORDER BY ts DESC, id DESC LIMIT 1` を維持して表示用の最新 reason と `NO_TRADE_EXIT` の存在を返す。隣の集約用 LATERAL は全 `NO_TRADE_EXIT` を対象に、`rejectionCode` を持たない行が 1 件でもあるかを `BOOL_OR` で返す。payload は TEXT なので、既存の `pg_input_is_valid(payload, 'jsonb')` と `jsonb_exists(payload::jsonb, 'rejectionCode')` の安全な式を再利用し、invalid JSON も supersede できない証跡として扱う。行が 1 件もない場合の NULL は JDBC の `getBoolean` により false になる。

commit 済み trade decision があり、`NO_TRADE_EXIT` が存在し、かつ全行が `rejectionCode` を持つ場合だけ、no-entry 証跡と final reason を superseded とする。`rejectionCode` を持たない汎用 tool 失敗が 1 件でもあれば、記録順序にかかわらず最新 reason と no-entry outcome を残す。拒否イベント自体は診断価値があるため削除しない。

**代替案**: 再提出成功時に先行の `NO_TRADE_EXIT` を削除または無効化する。却下 — 監査イベントの遡及的な書き換えになり、「観測できなかった事象を後から作らない / 消さない」原則に反する。診断のための記録が消えると issue #316 の目的も損なう。

### D5: `COMMITTED` からの後退を禁止する

state 更新を次の規則にする。

- `IN_FLIGHT` へ進めるのは、現在が `COMMITTED` でないときだけ
- `COMMITTED` は上書きしない
- `REJECTED` は `NOT_ATTEMPTED` からのみ（既存の CAS を維持）

これにより「1 回目 commit → 2 回目 conflict」で audit terminal が `COMMITTED` から `UNKNOWN` へ落ちない。落ちると「約定判断が確定していない」と誤読され、paper 真実性の評価母集団が歪む。

**代替案**: 最後の要求の状態をそのまま反映する。却下 — 確定した事実を後続の失敗で消すことになり、「commit されたかもしれない」を「されていない」に変換しない原則に反する。

## Risks / Trade-offs

- **[accept ループが busy loop 化する]** → D4b の規則（accept 例外は種別を問わず抜ける、ループ条件は `isInterrupted`）で閉じる。テストで close 後に worker thread が終了することを確認する
- **[再提出成功後も先行拒否の `NO_TRADE_EXIT` が残り run が誤分類される]** → D4c で `rejectionCode` を持つ submission 拒否だけに commit 済み trade decision の優先規則を適用する。汎用 tool 失敗の理由と拒否イベントは診断のため残す
- **[close 時に処理中の要求が中断される]** → 既存挙動と同じ。`awaitTermination(500ms)` を超えたら `close()` が例外を投げる契約は維持し、in-flight transaction が `IN_FLIGHT`（audit では `UNKNOWN`）で残る既存テストも維持する
- **[LLM が拒否を繰り返して run が長引く]** → tool call 上限と LLM 側の timeout が上流で効いている。gateway 側に回数制限を足すと、正当な修正提出まで打ち切る危険がある
- **[複数提出により authority の並行性が露出する]** → 逐次処理のため、同一 gateway 内で並行にはならない。異なる gateway / phase 間の並行性は既存の authority テストが担保している

## Migration Plan

DB schema 変更なし。wire protocol 変更なし。app process と MCP subprocess は同一 image から起動するため、旧 client（1 要求で閉じる）は新 gateway に対してそのまま動く。ロールバックは revert で足りる。
