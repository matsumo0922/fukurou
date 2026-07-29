## Why

`/ops/llm-auth` の `logged_in` は credential marker file の存在だけで決まるため、token が失効していても「認証は正常」と報告し続ける。2026-07-23 の production 障害では Codex の refresh token が `refresh_token_reused` で失効し全 proposer run が失敗していた間も、監視 API は `codex: logged_in` を返し続け、原因特定が遅れた。監視 API が誤った安心を与えることが障害対応の遅延要因になっている。

## What Changes

- `LlmAuthStatus` に `token_suspect` を追加する。credential marker は存在するが、現在の credential を使った LLM invocation が認証失敗 evidence を残している状態を表す。
- Codex の output parser が、既存の認証 evidence 判定では捕捉できない credential lifecycle の失敗文言（`refresh_token_reused` / `token_expired` / `Failed to refresh token`）を独立に追跡する。
- LLM invocation が失敗 evidence を観測した時点で、in-process の live state に記録する。daemon tick の liveness を `MutableLlmDaemonTickStatus` が保持しているのと同じ形にし、監視 API は DB を検索しない。
- 再ログイン後に古い evidence が残り続けないよう、evidence にその run が使った credential source の mtime を持たせ、現在の marker mtime より古い世代の evidence は無視する。WebUI login と `docker exec` fallback login のどちらでも marker が更新されるため、両経路で降格が解除される。
- 成功した invocation では降格を解除しない。invocation は persistent credential source の per-run copy を使い source へ書き戻さないため、成功は source が今も有効であることの証拠にならない。
- marker の mtime を読めない場合は `logged_in` を維持せず `unknown` を返す。判定できない状態を「正常」と報告しない。
- evidence state を注入していない構成（既存 test double）では現行どおり marker 存在だけで `logged_in` を返す。

**BREAKING**: `/ops/llm-auth` の `status` に新しい値 `token_suspect` が現れる。既存 consumer（WebUI System 画面）は status 文字列をそのまま表示し、`logged_in` との完全一致で logged-in 件数を数えるため、`token_suspect` は自動的に「logged in ではない」として扱われる。

## Capabilities

### New Capabilities

- `llm-cli-auth-status-evidence`: `/ops/llm-auth` が返す provider 別 login 状態の判定根拠。marker 存在に加えて直近 invocation の認証失敗 evidence を反映する規則、evidence の観測窓、evidence 参照失敗時の fail-closed 挙動を定義する。

### Modified Capabilities

（なし。`llm-cli-invocation-contract` は invocation 側の failure classification を定義しており、その分類結果を監視 API がどう解釈するかは新 capability の範囲とする。）

## Impact

- `trading/.../invoker/DefaultLlmOutputParser.kt`: credential lifecycle 文言の独立追跡。
- `trading/.../invoker/DefaultLlmCommandRenderer.kt`: credential source の mtime 観測。
- `trading/.../runner/LlmInvocationAuditor.kt`: audit payload への診断 field 追加と、in-process evidence state の更新。
- 新規 `LlmAuthEvidenceState`: provider ごとの最後の失敗 evidence を保持する in-process state（`MutableLlmDaemonTickStatus` と同型）。
- `fukurou/src/main/kotlin/me/matsumo/fukurou/LlmAuthService.kt`: `LlmAuthStatus` 拡張と判定の変更。
- `fukurou/src/main/kotlin/me/matsumo/fukurou/Application.kt`: evidence state の wiring。
- `fukurou/src/main/kotlin/me/matsumo/fukurou/OpsRoutes.kt`: `/ops/llm-auth` の `.describe {}` に新 status 値を記載。
- 外部 API 呼び出し、LLM 起動、schema 変更、DB migration、DB query の追加、index の追加は伴わない。readiness・SafetyFloor・order lifecycle には触れない。
- docs: `docs/llm-obsidian-production-setup.md` の「login state は非 secret の credential marker file で判定する」記述を現在形で更新する。
