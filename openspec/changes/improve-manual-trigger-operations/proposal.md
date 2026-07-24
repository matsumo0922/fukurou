## Why

owner が障害復旧後の動作確認を行う `MANUAL` trigger が、hard cap には余力があるにもかかわらず `ENTRY_FILL` / `STOP_PROXIMITY` の reserve 保護に拒否され、rolling window が空くまで復旧確認できない。加えて production container の Codex fallback login は appuser の auth source を更新する必要があり、root 実行では成功表示だけを残して credential を保存できない。現行 image は `USER appuser` だが、復旧手順は暗黙の image default に依存せず実行 UID と保存確認を固定する必要がある。

## What Changes

- `MANUAL` trigger を hourly / daily の `ENTRY_FILL`・`STOP_PROXIMITY` reserve 保護から除外し、owner の明示的な起動が未使用 reserve の領域でも予約できるようにする。
- `MAX_INVOCATIONS_PER_HOUR` / `MAX_INVOCATIONS_PER_DAY` の hard cap は変更せず、`MANUAL` も上限到達時は従来どおり拒否する。
- reserve 保護域で `FLAT_HEARTBEAT` が拒否される一方、`MANUAL` が受理される回帰テストと、hard cap 到達時に `MANUAL` も拒否される回帰テストを追加する。
- production の Codex fallback login を appuser（UID 10001）で実行し、auth source `auth.json` の更新時刻で保存成功を確認する現在形の runbook に修正する。root 実行は成功表示にかかわらず使用しないことを明記する。
- #311 で runtime supervisor は撤去済みのため、issue 本文にある supervisor の spawn 拒否解除を目的とした container restart 手順は追加しない。

## Capabilities

### New Capabilities
- `llm-launch-budget-admission`: LLM 起動の hard cap、critical trigger reserve、owner 発行の `MANUAL` trigger に対する admission の優先関係を定義する。

### Modified Capabilities
- `llm-cli-invocation-contract`: production container の Codex fallback login が appuser の永続 auth source を更新し、保存結果を確認できる運用契約を追加する。

## Impact

- `trading/src/main/kotlin/me/matsumo/fukurou/trading/daemon/LlmLaunchReservationRepository.kt` の reserve protection 判定
- `trading/src/test/kotlin/me/matsumo/fukurou/trading/daemon/LlmLaunchReservationRepositoryTest.kt` の hourly reserve / hard cap 回帰テスト
- `docs/llm-obsidian-production-setup.md` の Codex fallback login と保存確認手順
- `docs/mcp-runtime.md` の LLM 起動予算における `MANUAL` 例外の現在形記述
- `openspec/specs/llm-cli-invocation-contract/spec.md` への認証復旧契約の追加、および新しい `llm-launch-budget-admission` capability
- `/ops/trigger` の wire contract、認証・権限モデル、hard cap 設定値、schema、order lifecycle、paper execution semantics は変更しない
