## Context

`DefaultLlmAuthService.providerStatus()` は credential marker file が非空の regular file として存在すれば `LOGGED_IN` を返す。marker の中身も、その credential が実際に使えるかも見ない。2026-07-23 の障害では Codex の refresh token が失効し（`refresh_token_reused` → 401）全 proposer run が失敗していたが、marker file は残っていたため `/ops/llm-auth` は `codex: logged_in`（detail: `credential marker present`）を返し続けた。

失効の事実は監査記録（`command_event_log` の `RUNNER_PHASE_COMPLETED`）には残っていた。issue #295 で導入した output-interpreted 経路により、Codex の OUTPUT_CONTRACT failure で redaction 後の stderr が保存されるため、operator は PR #300 のデプロイ後に stderr 本文を読んで原因を特定できた。つまり evidence は既に DB にあり、監視 API がそれを見ていないだけである。

重要な制約が1つある。**当該障害の stderr は既存の認証 evidence 判定のどれにも一致しない**。

- `stderrAuthFailure`（primary category `AUTHENTICATION` の判定）は `CODEX_STDERR_AUTH_FAILURES` の2文言と stderr 全体の完全一致を要求する。障害時の stderr は複数行の log であり一致しない。
- `knownCompatibilityFailureCategory()` は `turn.failed` / `error` event の message を `trim()` 後の完全一致で分類する。障害時は JSONL event 自体が出ていない。
- `authEvidenceObserved` は `CODEX_KNOWN_AUTH_EVIDENCE_TEXTS`（上記2文言 + "Not logged in" / "Invalid authentication credentials"）の部分一致で立つ。`refresh_token_reused` も `token_expired` も含まれない。

したがって `authFailureSuspected` や `authEvidenceObserved` をそのまま監視 API へ繋ぐだけでは、issue が根拠として挙げた当の障害を検知できない。credential lifecycle の失敗文言を追跡する signal を新設する必要がある。

## Goals / Non-Goals

**Goals:**

- token 失効中に `/ops/llm-auth` が `logged_in` を返さない。issue に記録された実際の stderr（`refresh_token_reused` / `token_expired` / `Failed to refresh token`）を含む run が直近にあれば降格する。
- 正常時は `logged_in` を返す。run 履歴がない直後の状態も `logged_in` とする（失敗 evidence の不在は失敗ではない）。
- 再ログインで降格が解除される。WebUI login と `docker exec` fallback login の両方で解除される。
- 判定できない場合に「正常」と報告しない。
- 既存の raw output 保持 policy（issue #291 / #295）を変えない。

**Non-Goals:**

- token の自動再ログイン・自動リフレッシュ。
- 外部 API や CLI への実 probe。LLM 起動回数上限 policy に触れる経路を作らない。
- Claude 側の credential lifecycle 文言の網羅。Claude は既存の `authFailureSuspected` だけを evidence とする。
- 通知・alert 機構の新設。
- `/ops/monitoring` の provider outcome 集計の変更。
- readiness / SafetyFloor / order lifecycle への影響。

## Decisions

### D1: 監視 API は evidence を監査記録から読み、実 probe を行わない

issue の候補1（失効 evidence の反映）を採る。候補2（実 probe）は、Codex CLI に副作用のない軽量な認証検証 subcommand が存在せず、`codex login --device-auth` は新しい device flow を開始してしまう。`codex exec` 相当を叩けば LLM 起動回数 policy と衝突する。監視 endpoint が polling される（WebUI System は 30 秒間隔で refetch する）ことを踏まえると、実 probe は policy 上も負荷上も採用できない。

**代替案**: `auth.json` の JWT payload を読んで exp を検証する。却下する。credential file の中身を読むことは secret 境界を監視 layer へ広げる。また Codex の auth.json 形式は CLI の内部実装であり pin していないため、形式変更で silent に壊れる。

### D2: credential lifecycle failure を独立 signal として audit payload に出す

`RUNNER_PHASE_COMPLETED.details` に `authTokenFailureObserved` を追加する。`DefaultLlmOutputParser.parseCodex()` が、既存の `authEvidenceObserved` とは独立に、`CODEX_CREDENTIAL_LIFECYCLE_FAILURE_TEXTS` の部分一致を stdout / stderr に対して追跡する。

既存の `authEvidenceObserved` を拡張するのではなく別 signal にする理由は、`authEvidenceObserved` が raw output 保持の抑止条件として使われているため。文言を足すと OUTPUT_CONTRACT 系 failure の stdout / stderr が保存されなくなり、まさに今回の障害を人間が診断できた経路を塞ぐ。監視 status の強化と診断情報の抑止は別の関心事であり、片方の変更が他方の policy を動かしてはならない。

**代替案 A**: `CODEX_KNOWN_AUTH_EVIDENCE_TEXTS` に文言を追加する。却下する。上記のとおり raw output 保持 policy（`llm-cli-invocation-contract` の requirement）を変えてしまい、変更の scope が監視強化を超える。

**代替案 B**: auth service が audit payload に保存された redacted stderr を読み、そこで文言照合する。却下する。raw provider output を監視 layer へ持ち込むうえ、保持条件（issue #291 / #295 で2度変わっている）に依存するため、保持 policy が変わった瞬間に検知が silent に壊れる。

追跡する文言は、issue に記録された実 stderr から取る。

| 文言 | 根拠 |
|---|---|
| `refresh_token_reused` | 障害時の `codex_login::auth::manager` stderr |
| `token_expired` | 障害時の `codex_models_manager::manager` stderr |
| `Failed to refresh token` | 同上。refresh 失敗の一般形 |

`401 Unauthorized` は含めない。認証以外の 401（MCP や外部 HTTP の失敗が stdout へ混ざる場合）を credential 失効として誤検知するため。

### D3: evidence の観測窓は credential marker file の最終更新時刻から現在まで

再ログインすると Codex CLI は `auth.json` を書き直すため mtime が更新される。marker mtime より古い evidence は「既に解消済みの失効」として無視する。これにより WebUI login と `docker exec --user 10001 codex login --device-auth`（`llm-cli-invocation-contract` の Requirement「Production Codex fallback login updates the persistent auth source as appuser」で規定）のどちらでも降格が解除される。同 requirement は auth.json の mtime 更新を persistence の検査手段として既に定めており、本設計はその観測点を再利用する。

**代替案**: 固定の lookback window（直近 30 分など）にする。却下する。再ログイン直後でも window 内に古い failure が残っていれば降格したままになり、operator が「再ログインしたのに直らない」状態を見る。逆に window より前に失効したまま run が止まっている場合は検知できない。

### D4: provider ごとに「最新の1件」で判定する

窓内の該当 provider の `RUNNER_PHASE_COMPLETED` を新しい順に読み、最初に見つかった1件だけで判定する。その1件が failure signal を持てば `TOKEN_SUSPECT`、持たなければ `LOGGED_IN`。

成功した run は「その時点で token が使えた」ことの直接証拠であり、それより古い失敗を上書きするのが正しい。逆に窓内に該当 provider の run が1件もなければ、失敗の証拠はないため `LOGGED_IN` を維持する。

読み取りは bounded とする。`ORDER BY ts DESC LIMIT <bound>` で取得した payload を新しい順に走査し、該当 provider の最初の1件で打ち切る。bound 内に該当 provider が見つからなかった場合は「evidence なし」として `LOGGED_IN` とする。この endpoint は診断用であり readiness にも admission にも参加しないため、証拠の不在を失敗として扱う必要はない。

### D5: evidence 参照の失敗は `UNKNOWN`

DB 到達不能、query 失敗、payload が JSON として解釈できない、`details.provider` や signal 値が想定外 — いずれも `UNKNOWN` を返す。`LOGGED_IN` を維持しない。判定不能を「正常」と報告することが本 issue の原因そのものであるため、同じ誤りを新経路で作らない。

`MonitoringRepository` の malformed 扱いと同じ方針を採る。

### D6: evidence source 未注入時は現行動作を維持する

`DefaultLlmAuthService` の evidence source は nullable な constructor 引数とし、null なら marker 存在だけで `LOGGED_IN` を返す。DB を持たない構成（`docker compose` なしのローカル起動、既存の test）を壊さないため。production では `Application.kt` が DB 接続時に注入する。

### D7: PR を2段の stacked PR に分ける

- **PR 1（`:trading`）**: parser の credential lifecycle 追跡と、auditor による `authTokenFailureObserved` の payload 出力。監視側は変えない。
- **PR 2（`:fukurou`、base は PR 1）**: `TOKEN_SUSPECT` status、evidence repository、`DefaultLlmAuthService` の判定、`Application.kt` の wiring、route の `.describe {}`、docs。

PR 2 は PR 1 が出す payload field に依存するため、この順序でなければ PR 2 単独では観測 evidence を作れない。逆に PR 1 は単独で merge しても、新 field が payload に増えるだけで既存 consumer に影響しない（`MonitoringRepository` は未知 key を無視する）。

## Risks / Trade-offs

- **[既知文言の照合は不完全]** → CLI の log 文言が変われば検知が silent に落ちる。緩和: 文言集合を `internal` な定数として1箇所に置き、根拠（どの障害の stderr か）を KDoc に残す。加えて `authFailureSuspected`（primary category `AUTHENTICATION`）も evidence として併用するため、CLI が構造化された認証失敗を返す経路は文言に依存せず検知できる。これは完全性の主張ではなく、既知の失敗形に対する検知である旨を spec に明記する。
- **[marker mtime が更新されない再ログイン経路があると降格が解除されない]** → 誤って `token_suspect` のまま残る。緩和: root 実行 login が appuser の auth source を更新しない問題は `llm-cli-invocation-contract` で既に「使ってはならない手順」として規定済み。正規手順は auth.json を更新する。運用 doc に「再ログイン後も token_suspect が残る場合は auth.json の mtime を確認する」を追記する。
- **[監視 endpoint に DB read が増える]** → `/ops/llm-auth` が 30 秒間隔で polling されるため、1 回あたり provider 数ぶんの bounded read が走る。緩和: `MonitoringRepository` と同じ `statement_timeout` / `lock_timeout` 境界と row bound を適用する。既存の `idx_command_event_log_run_event_ts` は `decision_run_id` 先頭のため本 query では効かないが、`event_type` + `ts DESC` の bounded scan は既存の `/ops/monitoring` provider outcome query（30 分窓・1,000 行 bound）と同規模であり、新規 index は追加しない。
- **[status 値の追加が consumer を壊す]** → WebUI は status 文字列をそのまま表示し `logged_in` との完全一致で件数を数えるため、`token_suspect` は「logged in ではない」として自動的に正しく扱われる。緩和: WebUI の変更は不要だが、表示文言の確認を tasks に含める。
- **[Codex 限定]** → Claude の refresh token 失効は同じ精度で検知できない。issue の「やらないこと」に沿って先送りし、PR 2 の description に既知の限界として記録する。

## Migration Plan

DB schema 変更なし。`command_event_log` の payload に key が1つ増えるだけで、既存行の読み取りは影響を受けない（欠落 key は「signal なし」として扱う）。rollback は revert のみで足りる。

## Open Questions

なし。
