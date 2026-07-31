# falsifier-preview-protocol Specification

## Purpose
TBD - created by archiving change fix-falsifier-preview-cycle. Update Purpose after archive.
## Requirements
### Requirement: Falsifier は承認前 deterministic preview を利用しない
Issue #207 Phase 1。システムは、Falsifier phase の canonical tool policy、実行時 manifest、model-visible `tools/list` から `preview_order` を除外しなければならない（MUST）。Falsifier は intent、market、account、knowledge の read evidence から verdict を提出しなければならない（MUST）。

#### Scenario: Falsifier process の model-visible tool surface
- **WHEN** entry intent に対して production bootstrap 経路が Falsifier MCP server を構築する
- **THEN** `tools/list` は `get_trade_intent` と `submit_falsification` を含み、`preview_order` と `place_order` を含まない

#### Scenario: production-equivalent canary の tool policy
- **WHEN** production-equivalent CLI canary が Falsifier phase の MCP tool list を構築する
- **THEN** tool list は production Falsifier policy と同様に `preview_order` を含まない

### Requirement: deterministic preview は承認後 runner が実行する
Issue #207 Phase 1。システムは、fresh な APPROVED verdict を確認した後に限り、runner の production call path から `preview_order` 相当の broker evaluation を実行しなければならない（MUST）。runner は preview の後に同じ intent content で authoritative `place_order` を実行しなければならず（MUST）、preview rejection を Falsifier verdict へ遡及してはならない（MUST NOT）。

#### Scenario: APPROVED entry の production call path
- **WHEN** Proposer が entry intent を保存し、Falsifier が fresh な APPROVED verdict を保存する
- **THEN** runner は `preview_order`、`place_order` の順に実行し、Falsifier process はそのどちらも実行しない

#### Scenario: 承認後 preview が拒否される
- **WHEN** fresh な APPROVED verdict の後に runner の preview が SafetyFloor rejection を返す
- **THEN** runner は authoritative `place_order` で拒否を監査し、entry を作成せず、保存済み Falsifier verdict を書き換えない

