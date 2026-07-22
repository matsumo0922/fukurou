## Context

`LlmInvocationAuditor.phaseDetails()` は Codex provider の stdout/stderr を一律 `rawOutputOmitted=true` として監査 payload から破棄している。この挙動は 5日前に archive された OpenSpec change `2026-07-17-harden-llm-cli-lifecycle` が意図的に導入したもので、既存の `llm-cli-invocation-contract` spec には「Codex authentication fails → audit records AUTHENTICATION without persisting raw output, token, path, or prompt」という Requirement/Scenario、および「Pinned Codex emits a complete JSON event stream → ... without persisting raw output」という Requirement/Scenario が既にある。

issue #291 は、この隠蔽が #282 障害の原因究明に1週間かかったコストに見合わないと判断し、撤去を求めている。ただし既存コードを読むと、`rawOutputOmitted=true` になる分岐（`LlmInvocationAuditor.kt` L362-363）は provider 非依存で、`providerFailure` が立てば Claude でも省略される。Codex 固有の欠陥は「成功時（`providerFailure == null`）ですら常に省略する」という L371-375 の一箇所のみ。

### 独立反証で判明した設計上の欠陥（5ラウンドで収束）

falsifier（clean context, gpt-5.6-sol high）による反証を5ラウンド実施した。

**1ラウンド目:**
1. `SecretRedactor` は既知値の完全一致置換であり、token refresh 後の新しい secret は伏字にできない → **残存リスクとして受容**（Decision 5）
2. 成功時に Codex の stdout を保存する設計が、既存テスト `OpsRouteTest.opsRoutes_auditDoesNotExposeCodexRawOutputAndKeepsStructuredUsage`（取引戦略の中身が API に一切出ないことを保証）と衝突する → **成功時の記録方針は変更しない**（Decision 2）

**2ラウンド目:**
3. Codex parser の先勝ち方式カテゴリ決定（`providerCategory ?: ...`）により、`AUTHENTICATION` を除外するだけでは認証 evidence が `OUTPUT_CONTRACT`/`RATE_OR_SESSION_LIMIT`/`QUOTA_EXHAUSTED` に埋もれて漏れる → **ブロックリストからアローリストへ反転**（lifecycle カテゴリだけを許可）

**3ラウンド目（アローリストに `UNKNOWN_PROVIDER_FAILURE` を含めていた案の再反証）:**
4. `UNKNOWN_PROVIDER_FAILURE` は実は「純粋な lifecycle 事実」ではなく、`DefaultLlmOutputParser` が Codex JSONL の未知 error message から生成する parser 経由のカテゴリでもある。先勝ち方式により、認証 evidence がこのカテゴリにも埋もれる反例が実証された → **アローリストから `UNKNOWN_PROVIDER_FAILURE` を除外し、`PROCESS_EXIT`/`PROCESS_TIMEOUT`/`CLEANUP` の3つ（`processResult.status`/`exitCode`/cleanup 例外という、Codex の出力を一切解釈しない純粋な process 事実）だけに絞った**（Decision 1 を再々改訂）
5. issue #282 の実際の production 障害は `failureCategory=OUTPUT_CONTRACT, providerCode=SCHEMA_DRIFT` と確定しており（issue #282 本文・owner コメントで確認済み）、このカテゴリは安全のため raw output を残さない対象に含まれる。つまり **本 PR は #282 と全く同じ障害の再発時に stderr を確認できるようにはしない**。ユーザー確認の上、今回は lifecycle カテゴリだけの最小スコープで完了し、`OUTPUT_CONTRACT`（および他の Codex 文字列パターンマッチ系カテゴリ）を安全に診断可能にする作業は、parser が primary category と独立に「認証 evidence を観測したか」を追跡する、より大きな変更が必要なため別 issue に切り出す
6. lifecycle カテゴリの失敗でも、クラッシュ直前までの部分的な会話内容（取引戦略の断片）が stdout に残りうるという価値判断は、falsify protocol 上 reviewer 確認だけでは閉じられないと指摘され、ユーザーに明示確認した上で受容した（Decision 6）

**4ラウンド目（lifecycle カテゴリだけのアローリストの再反証）:**
7. `primaryProviderFailure()` は、adapter が Codex の出力テキストを解釈して `UNKNOWN_PROVIDER_FAILURE` を導出していても、process が非ゼロ終了・timeout・cleanup 失敗のいずれかであれば adapter の結果より lifecycle category を優先して返すため、「primary category が lifecycle 3カテゴリである」ことは「Codex の出力テキストが一切解釈されていない」ことを保証しないと判明した → **カテゴリ条件に加えて `invocationResult?.providerFailure == null`（`auditSignals.cliErrorReported == false`）を必須条件に追加**（Decision 1 を再改訂）

**5ラウンド目（`cliErrorReported == false` 条件の再反証）:**
8. `DefaultLlmOutputParser` の stderr 認証判定は `terminalCount == 0` のときだけ働くため、stdout に完全な成功 event stream が出力され `providerFailure == null` になるケースでも、stderr に既知の認証失敗文言が独立して含まれている可能性があり、`cliErrorReported == false` だけではこれを排除できないと判明した → **stderr が既知認証失敗文言セット（`CODEX_STDERR_AUTH_FAILURES`）を含まないことを3つ目の必須条件として追加**（Decision 1 を再々改訂、既存の known-string セットを再利用する mechanical な修正のためユーザー確認は不要と判断）

## Goals / Non-Goals

**Goals:**
- Codex が **`PROCESS_EXIT` / `PROCESS_TIMEOUT` / `CLEANUP`**（`processResult.status`・`exitCode`・cleanup 例外という、Codex の出力テキストを一切解釈しない純粋な process lifecycle 事実）**かつ** adapter が Codex の出力から何も検出しなかった（`invocationResult?.providerFailure == null`）**かつ** stderr が既知の認証失敗文言（`CODEX_STDERR_AUTH_FAILURES`）を含まないときだけ、redactor 経由の stdout/stderr を監査 payload に記録する
- `rawOutputOmitted` フィールドと関連ロジックを撤去する
- secret masking が Codex 出力に対して十分か確認する

**Non-Goals:**
- Claude の挙動変更（成功時・失敗時とも既存のまま）
- Codex の成功時の記録方針変更（既存のまま非公開を維持する）
- `AUTHENTICATION` / `RATE_OR_SESSION_LIMIT` / `QUOTA_EXHAUSTED` / `OUTPUT_CONTRACT` / `UNKNOWN_PROVIDER_FAILURE`（Codex の出力テキストを解釈して分類される全カテゴリ）の raw output 非保持方針の変更
- issue #282 と同形（`OUTPUT_CONTRACT`/`SCHEMA_DRIFT`）の障害を安全に診断可能にすること。これには parser が primary category と独立に認証 evidence の有無を追跡する仕組みが必要であり、本 PR のスコープを超える大きな変更のため別 issue に切り出す（下記「Follow-up」参照）
- 監査 payload の schema / テーブル構造の変更
- ログ基盤・監視・alert の新設
- `SecretRedactor` を出力解析型（token 発見的マスキング）に作り替えること
- `DefaultLlmOutputParser` のカテゴリ分類ロジック（先勝ち方式）自体の変更

## Decisions

### 1. Codex の raw output 記録を、純粋な process lifecycle カテゴリ・adapter failure なし・既知認証文言なしの3条件がすべて揃った場合だけのアローリストにする（ユーザー確認済み、5ラウンドの falsify を経た最終形）

`providerFailure != null` の分岐を次のように書く。

- Codex かつ次の3条件をすべて満たす場合 → `redactor.redactAndTruncate(stdout)` / `redactor.redactAndTruncate(stderr)` を記録する
  1. `failure.category` が `PROCESS_EXIT` / `PROCESS_TIMEOUT` / `CLEANUP` のいずれか
  2. `auditSignals.cliErrorReported`（`invocationResult?.providerFailure != null` を表す既存シグナル）が `false`（adapter が一切何も検出しなかった）
  3. `processResult.stderr` が `DefaultLlmOutputParser` の既知認証失敗文言セット（`CODEX_STDERR_AUTH_FAILURES`）のいずれも含まない
- それ以外（Claude の任意カテゴリ、または Codex の `AUTHENTICATION` / `RATE_OR_SESSION_LIMIT` / `QUOTA_EXHAUSTED` / `OUTPUT_CONTRACT` / `UNKNOWN_PROVIDER_FAILURE`、または上記3条件のいずれか1つでも欠ける複合ケース） → 現状どおり `stdout`/`stderr` キーを出さない

**4ラウンド目で判明した欠陥**: `primaryProviderFailure()` は、adapter（parser）が Codex の出力テキストを解釈して `UNKNOWN_PROVIDER_FAILURE` を生成していても、process が非ゼロ終了・timeout・cleanup 失敗のいずれかであれば、adapter の結果より lifecycle category を優先して返す（`LlmInvocationAuditor.kt` L510-522）。これを閉じるため、条件2（`cliErrorReported == false`）を追加した。

**5ラウンド目で判明した追加の欠陥**: `DefaultLlmOutputParser.parseCodex()` の stderr 認証判定（`stderrAuthFailure`）は `terminalCount == 0 && exitCode != 0 && stderr が既知文言と完全一致` の場合だけに限定されている（`DefaultLlmOutputParser.kt` L149-153）。つまり、stdout に完全な成功 event stream（`thread.started`/`item.completed`/`turn.completed`）が出力されていて `terminalCount == 1` の場合、parser は stderr の認証判定を一切行わない。この状態で process の exit code が非ゼロ・timeout・cleanup 失敗のいずれかになると、`providerFailure == null`（`successfulContractComplete` が成立するため）のまま primary category が lifecycle 3カテゴリになり、stderr に既知の認証失敗文言（`CODEX_STDERR_AUTH_FAILURES` のいずれか）が含まれていても `cliErrorReported == false` の判定はすり抜けてしまう反例が実証された。

これを閉じるため、条件3（stderr が既知認証失敗文言セットを含まない）を追加する。`CODEX_STDERR_AUTH_FAILURES` は `DefaultLlmOutputParser.kt` の private 定数だが、`internal` に変更して `LlmInvocationAuditor.kt` から参照する。parser 自身の判定は完全一致（`==`）だが、auditor 側の追加チェックは stderr 全体に既知文言が部分文字列として含まれるかどうか（`contains`）で判定し、parser の判定条件（`terminalCount == 0` 限定）よりも広く・保守的にする。

検討の経緯（5ラウンドの falsify）：
- 当初案（ブロックリスト、`AUTHENTICATION` のみ除外）→ 認証 evidence が `OUTPUT_CONTRACT` に分類されるケースで破綻（1ラウンド目）
- `OUTPUT_CONTRACT` も除外に追加 → 認証 evidence が `RATE_OR_SESSION_LIMIT`/`QUOTA_EXHAUSTED` に埋もれるケースで破綻（2ラウンド目）
- ブロックリストをやめてアローリスト（lifecycle カテゴリ許可）に反転、当初は `UNKNOWN_PROVIDER_FAILURE` も含めていた → `UNKNOWN_PROVIDER_FAILURE` も parser 経由のカテゴリであり、同じ先勝ち方式の反例が成立することが判明（3ラウンド目）
- カテゴリ条件に `cliErrorReported == false` を追加 → adapter が output text から何かを検出した複合ケースは閉じたが、parser の stderr 認証判定自体が `terminalCount == 0` 限定であるため、成功 terminal + 既知認証文言 stderr + 非ゼロ終了/timeout/cleanup失敗の組み合わせで漏れることが判明（4→5ラウンド目）
- **最終案**: カテゴリ条件・`cliErrorReported == false`・stderr の既知認証文言非含有、の3条件すべてを満たす場合だけを安全な境界とする

### 2. Codex の成功時は挙動を変更しない（ユーザー確認済み）

Codex が成功したとき（`providerFailure == null`）は、既存どおり `stdout`/`stderr` を記録しない。既存テスト `OpsRouteTest.opsRoutes_auditDoesNotExposeCodexRawOutputAndKeepsStructuredUsage` が、Codex 成功時の stdout/stderr に取引戦略の中身が含まれていても API に一切出ないことを保証しており、これを破ることは既存の非退行 invariant への regression になるため。既存 spec の Requirement「Pinned CLI output is parsed as a versioned contract」は変更不要。

### 3. `AUTHENTICATION` / `RATE_OR_SESSION_LIMIT` / `QUOTA_EXHAUSTED` / `OUTPUT_CONTRACT` / `UNKNOWN_PROVIDER_FAILURE` は Codex でも raw output を一切残さない（ユーザー確認済み、既存 spec 維持・拡張）

これら5カテゴリはすべて、`DefaultLlmOutputParser` が Codex の出力テキストを読んで分類する。この分類ロジックは先勝ち方式であり、認証 evidence が別カテゴリに埋もれる反例が3ラウンドにわたって実証されたため、5カテゴリすべてで raw output・token・path・prompt を一切残さない。

### 4. `rawOutputOmitted` は完全撤去し、フィールド自体を出さない（agent 仮決め）

記録しない場合、代替の「省略マーカー」フィールドは追加しない。`stdout`/`stderr` キーが存在しないことそのものが「記録していない」ことを表す。

### 5. `SecretRedactor` は既知値の完全一致置換のまま維持し、rotation 後の値が伏字にならない残存リスクを明示的に受容する（ユーザー確認済み）

single-owner 構成で診断不能インシデントの再発を防ぐ利益がこの残存リスクを上回ると判断し、redactor を出力解析型に作り替えることはしない。

### 6. lifecycle カテゴリの失敗でも、クラッシュ直前までの部分的な会話内容が stdout に残る可能性を許容する（ユーザー確認済み）

`PROCESS_TIMEOUT`/`PROCESS_EXIT`/`CLEANUP` は「プロセスが完走しなかった」ことだけを意味し、クラッシュに至るまでに Codex が何ターンか会話を進めていた場合、その分の `agent_message`/`tool_call` 内容は既に stdout に出力済みである。したがってこのアローリストでも、失敗直前までの取引戦略の断片が監査 payload に残る可能性はゼロではない。

falsify protocol 上、この価値判断は agent 仮決めや reviewer 確認だけでは閉じられないと指摘され、ユーザーに明示確認した上で受容した。secret 自体は redactor が masking する。

### 7. issue #282 と同形の障害（`OUTPUT_CONTRACT`/`SCHEMA_DRIFT`）の診断改善は別 issue に切り出す（ユーザー確認済み）

issue #282 の実際の production 障害は `failureCategory=OUTPUT_CONTRACT, providerCode=SCHEMA_DRIFT` だったことが issue 本文・owner コメントで確認できた。本 PR のアローリスト（lifecycle カテゴリのみ）では、このカテゴリの raw output は今後も記録されないため、**#282 と全く同じ障害が再発しても、本 PR の変更だけでは stderr を確認できない**。

`OUTPUT_CONTRACT` を安全に公開するには、parser が primary category と独立に「認証 evidence を観測したか」を追跡する仕組みが必要であり、`DefaultLlmOutputParser` と `LlmProviderFailure` モデルの拡張を伴う、本 PR のスコープを超える変更になる。ユーザー確認の上、今回は次の理由でこの変更を見送り、別 issue（follow-up）に切り出す。

- 実装コストとレビュー負荷が本 issue の受け入れ条件に対して不釣り合いに大きい
- 本 PR は `PROCESS_EXIT`/`PROCESS_TIMEOUT`/`CLEANUP` という別クラスの診断可能性ギャップを安全に改善するという、独立した価値を持つ

### レビューで判明した実効範囲の訂正（reviewer 指摘）

当初、本 PR の価値を「プロセスが起動さえできず即座に非ゼロ終了するケース（launcher 起動失敗等）を診断可能にする」と説明していたが、これは誤りだった。`DefaultLlmOutputParser.parseCodex()` は、有効な JSONL イベントを一切生成できない起動失敗（stdout が空・非 JSON 等）を `schemaDrift` 経由で `OUTPUT_CONTRACT` に分類する。`OUTPUT_CONTRACT` は除外カテゴリのままなので、**launcher 起動失敗のような「一度も成功 turn を生成できない」失敗は、本 PR 後も引き続き診断できない**。

`isSafeCodexLifecycleFailure()` が実際に成立しうるのは、`invocationResult.providerFailure == null`（= `successfulContractComplete` が成立、つまり `thread.started`/`item.completed`/`turn.completed` が揃った**完全な成功 turn**）が先に確定した上で、**その後**の process 終了処理が非ゼロ終了・timeout・cleanup 例外のいずれかになった場合だけである。つまり本 PR が実際に診断可能にするのは「会話 turn 自体は完走したが、teardown/終了処理レベルで失敗した」という、当初の想定より狭いクラスの障害である。

タスク・テスト（`LlmInvocationAuditorTest.kt`）はこの実効範囲に合わせて、stdout に完全な成功 event stream を伴う fixture に修正済み。

## Risks / Trade-offs

- [Risk] `SecretRedactor` は known-value 完全一致置換であり、token rotation 後の新しい secret や、環境変数名にマッチしない未知形状の secret は伏字にならない → Mitigation: 残存リスクとして受容する（Decision 5）
- [Risk] lifecycle カテゴリの失敗でも、クラッシュ直前までの部分的な会話内容（取引戦略の断片）が stdout に残りうる → Mitigation: 意図的な trade-off として受容する（Decision 6）。secret 自体は redactor が masking する
- [Risk] `AUTHENTICATION`/`RATE_OR_SESSION_LIMIT`/`QUOTA_EXHAUSTED`/`OUTPUT_CONTRACT`/`UNKNOWN_PROVIDER_FAILURE` は今回も redact 済み出力すら見せないため、Codex のこれらの障害（#282 の実障害である `OUTPUT_CONTRACT`/`SCHEMA_DRIFT` を含む）は今回の修正後も診断が難しいままになる → 許容し、別 issue に切り出す（Decision 7）。issue #291 の受け入れ条件「Codex run の失敗時」は無限定の文言だが、実装は `PROCESS_EXIT`/`PROCESS_TIMEOUT`/`CLEANUP` の3カテゴリに限定されることを PR description に明記する
- [Risk] Claude/Codex で挙動が非対称になる（成功時・parser 分類系失敗時は Codex だけ非公開のまま、process lifecycle 失敗時だけ Codex が公開される）→ Mitigation: delta spec の Scenario と design.md にアローリストの理由を明記する

## Migration Plan

なし（新しい監査フィールドの追加のみで、既存データの migration・rescale は不要）。

## Follow-up（別 issue へ切り出す）

issue #282 と同形の `OUTPUT_CONTRACT`/`SCHEMA_DRIFT` 障害を安全に診断可能にするには、`DefaultLlmOutputParser` が primary category（先勝ち方式）とは独立に「認証関連の evidence を出力中に一度でも観測したか」を追跡し、観測していない場合に限り `OUTPUT_CONTRACT`（および `RATE_OR_SESSION_LIMIT`/`QUOTA_EXHAUSTED`/`UNKNOWN_PROVIDER_FAILURE`）の raw output を記録する、という設計が必要。PR 作成時に別 issue として起票する。

## Open Questions

（なし。ユーザー確認済みの決定のみで実装に入る）
