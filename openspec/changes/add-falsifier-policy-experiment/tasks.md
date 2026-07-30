## 1. Runtime policy

- [ ] 1.1 version 付き Falsifier policy を typed config、runtime catalog、validation に追加する
- [ ] 1.2 conditional policy の deterministic evaluator と recent current-cohort outcome 読み取りを追加する

## 2. Runner and SafetyFloor

- [ ] 2.1 entry ごとに policy を一度評価し、bounded command event を記録する
- [ ] 2.2 policy に従って Falsifier を起動または省略し、偽の falsification を作らない
- [ ] 2.3 code-owned gate requirement を SafetyFloor へ渡し、intent integrity と他の rule を維持する

## 3. Regression evidence

- [ ] 3.1 ALWAYS_ON / OFF / CONDITIONAL と failure fail-safe の runner test を追加する
- [ ] 3.2 OFF でも intent mismatch / consumed / stop loss を拒否し、MCP caller が bypass できない test を追加する
- [ ] 3.3 production persistence の recent current-cohort outcome 読み取りを test する

## 4. Documentation and validation

- [ ] 4.1 prompt と設計・runtime・deploy docs を現在形で更新する
- [ ] 4.2 non-overlapping window、fixed-attribution、descriptive comparison の SQL / 運用手順を追加する
- [ ] 4.3 OpenSpec validation、targeted test、full test / detekt / build を実行する
