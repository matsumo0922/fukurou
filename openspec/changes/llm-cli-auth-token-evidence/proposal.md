## Why

`/ops/llm-auth` の `logged_in` は credential marker file の存在だけで決まるため、token が失効していても「認証は正常」と報告し続ける。2026-07-23 の production 障害では Codex の refresh token が `refresh_token_reused` で失効し全 proposer run が失敗していた間も、監視 API は `codex: logged_in` を返し続け、原因特定が遅れた。監視 API が誤った安心を与えることが障害対応の遅延要因になっている。

## What Changes

- `LlmAuthStatus` に `token_suspect` を追加する。credential marker は存在するが、直近の LLM invocation が認証失敗 evidence を残している状態を表す。
- `DefaultLlmAuthService.snapshot()` が、marker を検出した provider について、既存の invocation 監査記録（`command_event_log` の `RUNNER_PHASE_COMPLETED`）に認証失敗 evidence があるかを bounded read で確認する。evidence があれば `logged_in` から `token_suspect` へ降格する。
- 再ログイン後に古い evidence が残り続けないよう、evidence の観測範囲を credential marker file の最終更新時刻以降に限定する。WebUI login と `docker exec` fallback login のどちらでも marker が更新されるため、両経路で降格が解除される。
- evidence 参照が失敗した場合（DB 到達不能、監査 payload が解釈不能）は `logged_in` を維持せず `unknown` を返す。判定できない状態を「正常」と報告しない。
- evidence source を注入していない構成（DB なし、既存 test double）では現行どおり marker 存在だけで `logged_in` を返す。

**BREAKING**: `/ops/llm-auth` の `status` に新しい値 `token_suspect` が現れる。既存 consumer（WebUI System 画面）は status 文字列をそのまま表示し、`logged_in` との完全一致で logged-in 件数を数えるため、`token_suspect` は自動的に「logged in ではない」として扱われる。

## Capabilities

### New Capabilities

- `llm-cli-auth-status-evidence`: `/ops/llm-auth` が返す provider 別 login 状態の判定根拠。marker 存在に加えて直近 invocation の認証失敗 evidence を反映する規則、evidence の観測窓、evidence 参照失敗時の fail-closed 挙動を定義する。

### Modified Capabilities

（なし。`llm-cli-invocation-contract` は invocation 側の failure classification を定義しており、その分類結果を監視 API がどう解釈するかは新 capability の範囲とする。）

## Impact

- `fukurou/src/main/kotlin/me/matsumo/fukurou/LlmAuthService.kt`: `LlmAuthStatus` 拡張、`providerStatus()` の判定、evidence source 境界の追加。
- `fukurou/src/main/kotlin/me/matsumo/fukurou/Application.kt`: evidence source の wiring。
- 新規 evidence source 実装: `command_event_log` を bounded read する repository（`ExposedMonitoringRepository` と同じ read-only 境界のパターン）。
- `fukurou/src/main/kotlin/me/matsumo/fukurou/OpsRoutes.kt`: `/ops/llm-auth` の `.describe {}` に新 status 値を記載。
- 外部 API 呼び出し、LLM 起動、schema 変更、DB migration は伴わない。readiness・SafetyFloor・order lifecycle には触れない。
- docs: `docs/llm-obsidian-production-setup.md` の「login state は非 secret の credential marker file で判定する」記述を現在形で更新する。
