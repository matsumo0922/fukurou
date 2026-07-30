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

システムは SafetyFloor と同じ snapshot / risk calculator による post-order group risk が最大 1 trade risk の 50% 以上、不利または不明な regime、active epoch / current cohort の直近 2 連敗のいずれかで Falsifier を起動しなければならない（MUST）。
policy 入力の取得に失敗または欠損がある場合は Falsifier を起動しなければならない（MUST）。
recent outcome は最新 2 closed position を先に固定し、attribution / gap / cohort / execution semantics の unknown row を飛ばして古い trade へ遡ってはならない（MUST NOT）。

#### Scenario: 小口かつ有利 regime で連敗がない

- **WHEN** post-order group risk が上限の 50% 未満で `regime:trend_up` があり、current cohort の直近 2 trade が連敗ではない
- **THEN** Falsifier を起動しない

#### Scenario: recent outcome を取得できない

- **WHEN** current cohort の recent closed trade 読み取りに失敗する
- **THEN** Falsifier を起動し、unknown reason を監査する

### Requirement: policy decision を append-only audit に記録する

システムは entry intent ごとに policy decision ID、policy version、required、bounded reason、intent ID、runtime config version ID / hash を一意な durable record と command event に記録しなければならない（MUST）。
異なる payload で同じ intent の decision を上書きしてはならず（MUST NOT）、command event append に失敗した decision から entry を実行してはならない（MUST NOT）。
Falsifier を起動しない decision のために falsification verdict を作ってはならない（MUST NOT）。

#### Scenario: OFF policy を監査する

- **WHEN** `OFF_V1` で entry intent を評価する
- **THEN** required=false の policy event が記録され、falsifications row は作られない

### Requirement: policy 非起動でも intent integrity と SafetyFloor を維持する

システムは policy が Falsifier 不要と判定しても、permit の decision ID / intent ID / policy version / runtime config hash と durable policy decision の一致、persisted intent の存在、未消費、command との完全一致を検証しなければならない（MUST）。
Falsifier gate 以外の SafetyFloor rule を省略してはならない（MUST NOT）。
MCP caller が Falsifier 不要を指定できてはならない（MUST NOT）。

#### Scenario: OFF policy でも stop loss 違反を拒否する

- **WHEN** `OFF_V1` の entry が protective stop 必須条件を満たさない
- **THEN** SafetyFloor は entry を拒否し、falsification verdict は作られない

### Requirement: runtime activation 時刻から実効 policy を推測しない

システムは各 intent の durable policy decision を実効 policy の正本としなければならない（MUST）。
next-restart activation の `activated_at` から、再起動前の run / intent を新 policy へ帰属させてはならない（MUST NOT）。

#### Scenario: activation 後に再起動していない

- **WHEN** new policy を activate した後、旧 process が entry intent を評価する
- **THEN** intent は旧 process が保存した policy decision に帰属し、new policy window へ推測で移されない
