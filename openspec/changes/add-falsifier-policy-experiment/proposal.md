## Why

Issue #207 Phase 2 は Falsifier の常時 ON / OFF / 条件起動を別期間で比較する。
挙動を切り替える前に、実効 policy を intent 単位で一意に保存し、偽の falsification を作らず後続 PR が安全に参照できる durable boundary が必要になる。

## What Changes

- `ALWAYS_ON_V1` / `OFF_V1` / `CONDITIONAL_V1` を version 付き runtime config enum として追加する
- intent ごとの Falsifier policy decision を append-only に保存する
- policy decision と bounded audit event を同じ transaction で保存し、lost ACK retry を idempotent に収束させる
- in-memory / PostgreSQL repository と bootstrap schema の回帰テストを追加する

この PR は foundation のみで、runner / SafetyFloor の挙動を変更しない。

## Capabilities

### New Capabilities

- `falsifier-policy-foundation`: version 付き policy と intent 単位 durable decision の保存契約

### Modified Capabilities

- なし

## Impact

- typed config / runtime config catalog / validation
- decision domain / repository
- PostgreSQL bootstrap schema と persistence
- config / persistence docs

## Out of Scope

- Falsifier の起動 / 省略
- SafetyFloor permit
- conditional predicate
- rejected-intent shadow と期間比較
- production runtime config activation
