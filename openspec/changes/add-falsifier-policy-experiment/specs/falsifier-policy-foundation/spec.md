# falsifier-policy-foundation Specification

## Purpose

Falsifier strategy experiment が使う version 付き policy と intent 単位 durable decision の保存契約を定める。

## ADDED Requirements

### Requirement: Falsifier policy は version 付き runtime config で解決する

システムは `ALWAYS_ON_V1` / `OFF_V1` / `CONDITIONAL_V1` のいずれかを typed runtime config へ解決しなければならない（MUST）。
既定値は `ALWAYS_ON_V1` とし、不明な値は draft validation で拒否しなければならない（MUST）。
foundation 単独では runner behavior を変更してはならない（MUST NOT）。

#### Scenario: foundation だけを deploy する

- **WHEN** policy key が `ALWAYS_ON_V1` の foundation image を起動する
- **THEN** entry は従来どおり Falsifier を必須とする

### Requirement: policy decision は intent ごとに一意に保存する

システムは policy decision ID、intent ID、policy version、required、bounded reason codes、runtime config version ID / hash、created at を append-only に保存しなければならない（MUST）。
intent ID は一意でなければならない（MUST）。
raw prompt、自由文、価格、secret を保存してはならない（MUST NOT）。

#### Scenario: intent の policy decision を保存する

- **WHEN** 未記録の intent に canonical policy decision を保存する
- **THEN** intent に一意な decision row が返る

### Requirement: decision と canonical audit event は atomic / idempotent に保存する

システムは policy decision と `FALSIFIER_POLICY_EVALUATED` event を同じ transaction で保存しなければならない（MUST）。
event payload は decision の canonical projection から生成しなければならず（MUST）、caller 任意 JSON を受け取ってはならない（MUST NOT）。
同じ intent / decision ID / payload の retry は成功し、重複 row を作ってはならない（MUST NOT）。
異なる payload、decision / event の片側欠損、event payload 不一致は conflict として拒否しなければならない（MUST）。

#### Scenario: commit 後に ACK を失う

- **WHEN** decision と event の transaction は commit したが caller が応答を失い、同じ request を retry する
- **THEN** repository は既存 decision / event を exact readback して成功し、row を増やさない

#### Scenario: 同じ intent に異なる payload を送る

- **WHEN** 既存 intent の policy version または required を変えた request を送る
- **THEN** repository は conflict を返し、既存 row を変更しない

### Requirement: foundation の非既定 policy は production activation しない

運用手順は policy-application change が merge / deploy されるまで `OFF_V1` / `CONDITIONAL_V1` の production activation を禁止しなければならない（MUST）。

#### Scenario: foundation deploy 後に config を確認する

- **WHEN** operator が runtime config catalog を確認する
- **THEN** policy key は表示され、docs は非既定値をまだ active 化しないことを示す
