## Context

Phase 2 の policy 適用は runtime config、runner、SafetyFloor、evaluation を横断する。
1 PR 1,000 行の目安を守り、最初の rollback 単位は config と durable attribution foundation に限定する。
後続 PR はこの foundation の reviewer `APPROVED` 後に着手する。

## Goals / Non-Goals

**Goals**

- version 付き Falsifier policy を typed runtime config へ追加する
- intent ごとに一つの policy decision を append-only に保存する
- decision と audit event の atomicity / idempotency を保証する
- 後続 runner が durable decision を exact readback できる

**Non-Goals**

- runner / SafetyFloor の挙動変更
- conditional policy の評価
- production activation
- descriptive comparison

## Decisions

### 1. Policy 名が semantic version を兼ねる

runtime key `decision.falsifierPolicy` は次の enum だけを受け入れる。

- `ALWAYS_ON_V1`
- `OFF_V1`
- `CONDITIONAL_V1`

default は既存挙動を表す `ALWAYS_ON_V1`。
この foundation では値を解決・監査可能にするだけで、runner は従来どおり全 entry で Falsifier を必須とする。

### 2. Durable decision は intent ID を unique key にする

`falsifier_policy_decisions` は次を保持する。

- policy decision ID
- intent ID（unique）
- policy version
- required
- sorted bounded reason codes
- runtime config version ID / hash
- created at

reason code は後続 policy 適用で使う bounded enum を先に定義する。
raw prompt、自由文、価格、secret は保存しない。

### 3. Decision と audit event は同じ transaction で保存する

repository の `recordFalsifierPolicyDecision` は policy decision row と `FALSIFIER_POLICY_EVALUATED` command event を同じ transaction で insert する。
event payload は policy decision の canonical projection から生成し、caller から任意 JSON を受け取らない。

同じ intent / decision ID / canonical payload の retry は既存 decision と event を exact readback して成功する。
同じ intent または decision ID の異なる payload、decision と event の片側欠損、event payload 不一致は conflict として fail closed にする。
transaction commit 後の ACK loss でも retry は重複 row を作らない。

in-memory repository も同じ atomic contract を模倣する。

### 4. 実効期間はまだ開始しない

runtime config activation は next restart で反映されるが、この foundation だけでは runner behavior を切り替えない。
運用 docs は policy key を「foundation のみ・activation 禁止」と明記する。
後続 policy-application PR が merge / deploy されるまで `OFF_V1` / `CONDITIONAL_V1` を production で active 化しない。

## Risks / Trade-offs

- [config 値だけが先に見える] → docs で activation 禁止を明記し、runner behavior は常時 ON のまま維持する
- [lost ACK で retry が conflict する] → decision と canonical event を同一 transaction に置き、exact readback で同一 retry を成功扱いにする
- [片側 legacy row] → 自動修復せず conflict として fail closed にする

## Rollback Plan

code default は `ALWAYS_ON_V1` のため、foundation を rollback しても runner behavior は変わらない。
作成済み policy decision / event は監査 row として残し、destructive rollback は行わない。
