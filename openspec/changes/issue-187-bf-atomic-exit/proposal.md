## Why

Issue #187 の現行 paper lifecycle では、full EXIT が open position と同一 thesis の pending BUY を一つの原子操作として無効化しないため、EXIT 後に resting BUY が約定して再エントリーできる。また、HARD_HALT の注文取消と建玉 close は複数 transaction に分かれ、途中停止後の再試行で安全な終端へ収束する契約が不足している。

## What Changes

- （ユーザー確認済み）full EXIT を、対象 position の close と同一 thesis に属する risk-increasing pending BUY の取消を単一 DB transaction で行う操作へ変更する。
- （ユーザー確認済み）thesis linkage が missing、null、複数候補、または対象不一致なら mutation 0 で fail-closed にする。
- （ユーザー確認済み）EXIT と market-event fill を既存の lock order と status CAS で直列化し、EXIT commit 後の late entry を作らない。
- （ユーザー確認済み）HARD_HALT cleanup は同じ原子的な close/cancel primitive を再利用し、sticky HARD_HALT 中に startup と periodic reconciliation から冪等に再試行する。
- （agent 仮決め）`risk_state` に最小の `UNKNOWN / SAFE` cleanup evidence を additive に保存し、`UNKNOWN` 中の manual resume を拒否する。
- （ユーザー確認済み）crash 前は mutation 0、commit 後は対象 mutation 完了とし、結果を確定できない場合は SAFE と推定せず HARD_HALT/UNKNOWN を維持する。
- （ユーザー確認済み）全 crash point を覆う汎用 saga、multi-replica coordinator、root deploy artifact、無関係 thesis の一括取消は追加しない。

## Capabilities

### New Capabilities

- `atomic-paper-risk-exit`: Issue #187 DoD (b) と (f) のうち、atomic full EXIT、同一 thesis pending BUY invalidation、sticky HARD_HALT cleanup の再試行と終端契約を定義する。

### Modified Capabilities

なし。

## Impact

- `DecisionExecutionLifecycle` の EXIT 実行経路。
- `PaperBroker`、`PaperLedgerRepository`、Exposed/in-memory ledger 実装の close/cancel 境界。
- `ProtectionReconciler`、kill criterion、startup/runtime wiring の HARD_HALT cleanup 経路。
- PostgreSQL の order/position/thesis linkage、lock order、status CASと、`risk_state`へのadditive cleanup evidence。既存履歴のbackfillやdestructive migrationは行わない。
- runner、broker、persistence、reconciler の targeted/concurrency tests と `docs/design.md` / `docs/mcp-runtime.md`。
- 公開 API、OpenAPI、NAS root-installed artifact、production secret/config への影響はない。
