# falsifier-policy-experiment Specification

## Purpose

production paper trading の Falsifier を version 付き policy で期間比較し、OFF / 条件非該当を falsification verdict と混同せず監査する。

## ADDED Requirements

### Requirement: Falsifier policy は version 付き runtime config で選択する

システムは `ALWAYS_ON_V1` / `OFF_V1` / `CONDITIONAL_V1` のいずれかを active runtime config から解決しなければならない（MUST）。
既定値は `ALWAYS_ON_V1` とし、不明な値は runtime config validation で拒否しなければならない（MUST）。

#### Scenario: 不明な policy を拒否する

- **WHEN** runtime config draft が未定義の Falsifier policy を持つ
- **THEN** validation は draft を拒否する

### Requirement: CONDITIONAL_V1 は bounded predicate で起動を決める

システムは大口リスク、不利または不明な regime、current cohort の直近 2 連敗のいずれかで Falsifier を起動しなければならない（MUST）。
policy 入力の取得に失敗または欠損がある場合は Falsifier を起動しなければならない（MUST）。

#### Scenario: 小口かつ有利 regime で連敗がない

- **WHEN** planned risk が上限の 50% 未満で `regime:trend_up` があり、current cohort の直近 2 trade が連敗ではない
- **THEN** Falsifier を起動しない

#### Scenario: recent outcome を取得できない

- **WHEN** current cohort の recent closed trade 読み取りに失敗する
- **THEN** Falsifier を起動し、unknown reason を監査する

### Requirement: policy decision を append-only audit に記録する

システムは entry intent ごとに policy version、required、bounded reason、intent ID、runtime config version ID / hash を command event として記録しなければならない（MUST）。
Falsifier を起動しない decision のために falsification verdict を作ってはならない（MUST NOT）。

#### Scenario: OFF policy を監査する

- **WHEN** `OFF_V1` で entry intent を評価する
- **THEN** required=false の policy event が記録され、falsifications row は作られない

### Requirement: policy 非起動でも intent integrity と SafetyFloor を維持する

システムは policy が Falsifier 不要と判定しても、persisted intent の存在、未消費、command との完全一致を検証しなければならない（MUST）。
Falsifier gate 以外の SafetyFloor rule を省略してはならない（MUST NOT）。
MCP caller が Falsifier 不要を指定できてはならない（MUST NOT）。

#### Scenario: OFF policy でも stop loss 違反を拒否する

- **WHEN** `OFF_V1` の entry が protective stop 必須条件を満たさない
- **THEN** SafetyFloor は entry を拒否し、falsification verdict は作られない

### Requirement: 比較 window は重ねず attribution を固定する

運用手順は policy ごとに重ならない runtime activation window を記録し、Proposer assignment、prompt hash、SafetyFloor policy version、current account epoch / execution cohort の固定または変化を照合しなければならない（MUST）。
変化または attribution 欠損がある window を優劣判定へ黙って混ぜてはならない（MUST NOT）。

#### Scenario: window 内で prompt が変化する

- **WHEN** 同じ policy window に複数の system prompt hash が存在する
- **THEN** window は比較不能または別 cohort として報告される

### Requirement: descriptive comparison は unknown と shadow の意味を維持する

比較は proposal / required / approval / rejection / fill / post-cost outcome / unknown coverage を policy 別に集計しなければならない（MUST）。
gate-shadow の `CROSSED` を fill に変換してはならず（MUST NOT）、`UNKNOWN` を母集団から除外してはならない（MUST NOT）。

#### Scenario: shadow crossing がある

- **WHEN** TTL 失効 order に `CROSSED` resolution がある
- **THEN** crossing は補助観測として報告され、paper fill や realized outcome は作られない
