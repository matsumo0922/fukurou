## Why

Issue #207 の OFF 実験を安全に進めるには、A1 の authority / exact replay 境界の先で、同時 entry が flat account を二重に通過しない backend 原子性が必要である。
full A2 は human-authored diff が 1,250〜1,500 行規模と見込まれるため、本 change は実装時に1,000行以内をgateとするA2a backend capabilityへ分割し、production 接続を後続 A2b へ残す。

## What Changes

- module-internal の authorized atomic paper entry capability を InMemory と PostgreSQL backend に追加する
- capability の同一 in-memory lock / PostgreSQL transaction 内で、exact replay を最初に判定し、`Missing` の場合だけ intent の存在・未消費を検証する
- 同じ原子境界内で `open positions == 0 AND risk-increasing open entry orders == 0` を検証し、MARKET 相当の即時 entry または LIMIT / STOP の resting entry と intent consumption を一緒に保存する
- PostgreSQL の lock 順を MARKET と realtime eligibility 付き resting に分け、resting は session advisory lock と `market_data_sessions` row を ledger mutation rows より先に取得する
- InMemory は `decision mutex -> ledger write lock -> equity snapshot lock` の順で直列化し、equity lock を before-image 取得から成功または完全 restore まで保持することで、ledger publish 後・consumption 前の failure にも partial state を残さない
- exact replay は同じ client request ID の entry と正規 protective STOP だけを受理し、close / reduce / ADD_LONG や別 request ID の同一 trade group row を result へ集約しない
- exact replay、ambiguous replay、consumed intent、non-flat conflict、storage / commit failure を typed result / failure で区別する
- PostgreSQL transaction は `maxAttempts=1` とし、commit outcome 不明時は自動再実行せず fresh transaction の exact readback だけで回復する
- 同一 request、別 request、別 intent、MARKET と resting の並行実行を InMemory / PostgreSQL の双方で回帰テストする
- capability は既存 paper mutation request と write policy を再利用し、HARD_HALT、paper baseline、cash / protective STOP を含む既存 safety semantics を迂回する別経路を作らない
- A1 `AuthorizedFalsifierPolicyBoundary` は capability を呼ばず、exact replay が `Missing` の場合は従来どおり fail closed にする
- public `Broker`、`PlaceOrderCommand`、MCP schema、production runner、Falsifier 実行、runtime status / outcome mapping を変更しない
- inactive capability と後続 activation 境界を runtime docs に記録する

## Capabilities

### New Capabilities

- `falsifier-policy-atomic-entry-backend`: exact replay、intent consumption、flat predicate、MARKET / resting paper mutation を一つの backend 原子境界で実行する inactive internal capability

### Modified Capabilities

なし。

## Impact

- broker package の internal capability / typed result / failure
- `InMemoryDecisionRepository`、`InMemoryPaperLedgerRepository`、`InMemoryEquitySnapshotRepository` の共有 lock 順序
- `InMemoryEquitySnapshotRepository` の non-suspend exclusive snapshot transactionとinternal before-image restore
- `ExposedPaperLedgerRepository` / `ExposedPaperLedgerWriter` の path 別 lock 順、transaction retry / commit readback、replay・predicate・intent consumption
- InMemory unit test と PostgreSQL integration / concurrency test
- `docs/mcp-runtime.md`

DB schema migrationは追加しない。
A2b は A1 boundary と既存 SafetyFloor / paper preparation path を capability へ接続し、B は runtime activation、permit propagation、Falsifier skip、durable outcome mapping を扱う。
