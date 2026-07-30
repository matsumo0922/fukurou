## 1. Runtime policy

- [ ] 1.1 version 付き Falsifier policy を typed config、runtime catalog、validation に追加する
- [ ] 1.2 SafetyFloor と同じ risk calculator を使う conditional evaluator を追加する
- [ ] 1.3 active epoch / current cohort の最新 2 closed position を unknown を含めて読む query を追加する

## 2. Runner and SafetyFloor

- [ ] 2.1 intent ごとに idempotent な durable policy decision と bounded command event を記録する
- [ ] 2.2 policy に従って Falsifier を起動または省略し、偽の falsification を作らない
- [ ] 2.3 durable decision に identity-bound な code-owned permit を SafetyFloor へ渡し、intent integrity と他の rule を維持する

## 3. Regression evidence

- [ ] 3.1 ALWAYS_ON / OFF / CONDITIONAL と failure fail-safe の runner test を追加する
- [ ] 3.2 OFF でも permit mismatch / intent mismatch / consumed / stop loss を拒否し、MCP caller が bypass できない test を追加する
- [ ] 3.3 policy decision idempotency / conflict / event failure と production recent outcome query を test する

## 4. Documentation and validation

- [ ] 4.1 prompt と設計・runtime・deploy docs を現在形で更新する
- [ ] 4.2 activation 時刻ではなく durable policy decision を実効 attribution とする運用記述を追加する
- [ ] 4.3 OpenSpec validation、targeted test、full test / detekt / build を実行する
