## Context

Issue #290 は Epic #286 で決定した「single-owner の paper trading 環境に見合わない隔離機構を撤去する」方針の S3 に当たる。現在、`OneShotLlmRunner.mcpServerConfig()` は application database identity と別に `FUKUROU_MCP_DB_USER` を読み、未指定時は `DEFAULT_MCP_DATABASE_USER = "fukurou_mcp"` を launch manifest の `dbUser` に書く。production deploy は専用 script と SQL でこの role を provision し、schema 変更時には GRANT の追従が必要になる。

Issue #288 により MCP subprocess の起動は app と同じ service user、argv manifest id、literal environment password、manifest socket path を使う構成へ移行済みである。MCP の submission は引き続き app-owned gateway を通るため、専用 DB role の撤去で order lifecycle や paper truth の永続化境界を変更する必要はない。`McpPersistenceSchemaVerifier` も schema/readiness を検証しており、専用 role や ACL を前提としていない。

ただし専用 role の依存は Issue 本文に列挙された箇所より広い。`McpIsolationCanaryArtifacts` は `FUKUROU_MCP_DB_USER` を必須入力にし、`fukurou-deploy-db` は `mcp-role.sql` を DB helper payload manifest に含めて foundation SQL へ `mcp_role` を渡し、`deploy-foundation-v1.sql` は migration ごとに GRANT を追従する。deploy self-test もこの契約を固定している。さらに `McpDatabaseRoleIntegrationTest` は約700行にわたり provision script と role boundary を検証しながら MCP tool matrix を実行しているため、単純削除では回帰 coverage を失う。

## Goals / Non-Goals

**Goals:**

- MCP launch manifest の DB user を application database user と一致させる
- 専用 MCP role の env、default、provision script、SQL、deploy 手順を撤去する
- `submit_decision` の gateway 経由と既存の MCP tool behavior を維持する
- production DB に残る role の後始末を、明示的な owner migration note として提示する
- 関連する runtime・deploy・design ドキュメントを現在の構成へ更新する

**Non-Goals:**

- DB password の受け渡し方式を変更する
- PostgreSQL pool、timeout、schema verifier の一般的な挙動を変更する
- manifest の `dbUser` フィールドや manifest validation を削除・簡素化する
- MCP tool を submission gateway から直接 DB write へ変更する
- application role 自体の権限、migration ownership、backup/restore 契約を再設計する
- production DB の role を deploy や migration から自動削除する

## Decisions

### D1. Manifest の DB identity は application runtime の `DB_USER` を正本とする（agent 仮決め）

`OneShotLlmRunner.mcpServerConfig()` は独立した `FUKUROU_MCP_DB_USER` を解決せず、Ktor application と同じ database username を launch manifest の `dbUser` に設定する。production compose では `DB_USER` が `POSTGRES_USER` から注入されるため、production path と `McpIsolationCanaryArtifacts` は `DB_USER` を使用し、compose と `.env.example` から `FUKUROU_MCP_DB_USER` を削除する。manifest の `dbUser` フィールドは bootstrap と監査上の接続情報として残す。

代替案として `FUKUROU_MCP_DB_USER` の default だけを app user に変える方法は、不要な二重設定と将来の不一致余地を残すため採用しない。`POSTGRES_USER` を subprocess 設定から直接読む方法も、application が利用する runtime 名は `DB_USER` であるため採用しない。

### D2. 専用 role の provision 資産と deploy GRANT 追従は cutover で一括削除する（agent 仮決め）

cutover では `scripts/deploy/provision-fukurou-mcp-role` と `scripts/deploy/sql/mcp-role.sql` に加え、固定 payload list を共有する3箇所を同時に更新する。`Dockerfile` の `COPY` / hash list、`scripts/deploy/deploy-fukurou` の `MCP_ROLE_SQL` / installed manifest entry、`scripts/deploy/fukurou-deploy-db` の role env/default・payload manifest entry・`--set=mcp_role` が対象である。さらに `deploy-foundation-v1.sql` の role GRANT、`ReleaseDeployFoundationContractTest`、deploy executor / DB helper self-test の専用 role fixture/assertion を同時に削除または残存3-file contractへ更新する。runtime が app role を使う一方で image build が削除済み SQLを `COPY` したり、root deploy executor / DB helper が削除済み payload を検証したり、foundation migration が stale な role へ GRANT したりする中間状態を作らない。

代替案として runtime 切替と deploy cleanup を別 PR に分ける方法は、それぞれ単体で設定と運用契約を不整合にするため採用しない。PR を分ける場合も、本番挙動を変えない regression coverage の追加だけを先行させ、runtime・deploy・docs の cutover は一括で行う。

### D3. 書き込み境界は PostgreSQL role ではなく submission gateway で維持する（ユーザー確認済み）

MCP subprocess が application role を使っても、`submit_decision` の production path は manifest の `submissionSocketPath` を通じて app-owned gateway に接続し、gateway が validation と永続化を担う。MCP tool の実装を直接 INSERT へ変更せず、既存回帰テストで gateway 経由を確認する。

専用 role による DB ACL を残して defense-in-depth とする案は、Epic #286 で single-owner paper 環境に対する保守コストが便益を上回ると判断済みのため採用しない。

### D4. 残存 production role は cluster preflight と単一 transaction を通した owner 手順で削除する（agent 仮決め）

image deploy と application 起動は、旧 `fukurou_mcp` role が DB に残っていても成立する。一方、現行 provision は database CONNECT、schema USAGE、table SELECT / INSERT、catalog function EXECUTE を付与するため、`DROP ROLE fukurou_mcp;` 単独では dependent privilege が残って失敗する。また `REASSIGN OWNED` / `DROP OWNED` / `DROP ROLE` を autocommit で順に実行すると、最後の `DROP ROLE` が他 database の dependency で失敗した時点で application DB だけが変更済みになる。

PR 2 description の owner migration note は次の順序を要求する。

1. `pg_shdepend` と `pg_database` を application `POSTGRES_USER` で照合し、`fukurou_mcp` の dependency が application DB と shared database privilege 以外に存在しないこと、active session がないことを cluster-wide preflight する。別 database の table/function/default-ACL dependency が見つかった場合は変更を実行せず停止する。
2. application DB で最終手順と同じ transaction を `ROLLBACK` 終端で dry-run する。`DROP ROLE` は cluster 全体の dependency を検査するため、preflight が漏らした dependency があっても transaction 全体が rollback され、中間状態を残さない。

```sql
BEGIN; REASSIGN OWNED BY fukurou_mcp TO CURRENT_USER; DROP OWNED BY fukurou_mcp; DROP ROLE fukurou_mcp; ROLLBACK;
```

3. dry-run 成功後、同じ1行を `COMMIT` 終端で実行する。

```sql
BEGIN; REASSIGN OWNED BY fukurou_mcp TO CURRENT_USER; DROP OWNED BY fukurou_mcp; DROP ROLE fukurou_mcp; COMMIT;
```

`REASSIGN OWNED` は想定外に残った所有 object を application role へ保全し、`DROP OWNED` は application DB と shared object に付与された ACL dependency を除去する。明示 transaction により `DROP ROLE` が失敗した場合も ownership / ACL 変更を残さない。disposable PostgreSQL では application DB と別 database の両方に dependency を作り、preflight が別 database dependency を検出すること、dry-run failure が application DB を変更しないこと、別 database dependency 除去後の final transaction が role を削除して application-owned object を保持することを検証する。

適用時期は owner が PR 2 deploy の安定を確認して決める。deploy script、Flyway migration、startup hook からは実行しない。自動削除は rollback 時に旧 image が専用 role を必要とする可能性を奪い、DB 権限変更を application deploy に暗黙に混ぜるため採用しない。

### D5. 2 PR に分け、application-role coverage を先行して cutover を一括レビューする（ユーザー確認済み）

専用 role を固定する `McpDatabaseRoleIntegrationTest` は約700行あり、provision、dirty privilege repair、future object boundary、禁止 write assertion と、16 tool の required matrix coverage が同じクラスに混在する。これを単一 PR で runtime・deploy・docs の変更と同時に大幅削除すると、human-authored diff 1,000 行目安を超えやすく、「消した role 境界」と「残す tool/gateway 回帰」のレビューが埋もれる。そのため次の2 PRに分ける。

- **PR 1 — additive application-role regression coverage**: production 配線、専用 role provision、既存 role-boundary test は変更せず、application role で production bootstrap/server path の MCP tool matrix と submission gateway 永続化が成立する回帰シナリオを追加する。可能な限り既存 fixture/helper を共有し、専用の新 harness は作らない。この PR は本番挙動を変えず、cutover 後に残す coverage を先に確立する。
- **PR 2 — atomic runtime/deploy/docs cutover**: `OneShotLlmRunner` と canary を `DB_USER` へ切り替え、compose / `.env.example` の env を削除する。同じ PR で provision script / SQL、Dockerfile・root deploy executor・DB helper の同期 payload entry、foundation GRANT、deploy contract/self-test の role fixture、旧 role-boundary assertion を削除し、PR 1 で追加した application-role matrix を正本として残す。docs と owner migration note もここで更新する。

PR 1 後に既存 dedicated-role test を残すため、現行 production contract の coverage は弱まらない。PR 2 は本番 identity と deploy contract を一括で切り替えるため、stale な中間状態を作らない。OpenSpec change は PR 1 では archive せず、PR 2 完了後に一度だけ archive する。PR 2 を PR 1 branch に対する stacked PR とする場合は、PR 1 merge 後かつ base branch 削除前に PR 2 の base を `main` へ明示的に retarget する。

単一 PR は変更の原子性だけを見ると可能だが、約700行のテスト再編と deploy hidden dependency を含むため採用しない。3 PR 以上への細分化は runtime と deploy cleanup の不整合を招くため行わない。

### D6. PR 2 から旧 image へ戻すときは rollback SHA の DB helper artifact set を先に復元する（agent 仮決め）

PR 2 では DB helper marker の入力が4ファイルから3ファイルへ変わる。root-installed helper/marker が3-file contractのまま旧 imageを candidateにすると、旧 imageの4-file markerと一致せず `CANDIDATE_DB_HELPER_MARKER_MISMATCH` でcompose mutation前に停止する。roleを再provisionするだけでは rollback できない。

rollback は旧 imageを起動する前に、rollback SHAから `deploy-fukurou`、`fukurou-deploy-db`、`deploy-foundation-v1.sql`、`deploy-foundation-v1-indexes.sql`、`mcp-role.sql` をexactにroot配置し、そのrevisionの `fukurou-deploy-db write-install-marker` で4-file markerを再生成する。その後に旧 image digestを `workflow_dispatch` でdeployする。owner migration noteを実行済みの場合は、同じrollback SHAの provision script / SQLで `fukurou_mcp` roleを再作成してから旧 imageを起動する。

逆方向（4-file installed set → PR 2の3-file candidate）と rollback方向（3-file installed set →旧4-file candidate）の両方について、exact artifact setとmarkerを再配置すればcandidate verificationが通り、混在setではfail-closedになることをdeploy contract/self-testで検証する。rollback artifactの配置はroot operator作業であり自動化しない。

## Risks / Trade-offs

- [Risk] `DB_USER` と `POSTGRES_USER` が異なる環境では意図しない role を manifest に書く → Mitigation: application runtime の正本である `DB_USER` を使用し、production compose と回帰テストで `POSTGRES_USER` から同じ値が注入されることを確認する
- [Risk] app role に統合すると MCP subprocess が従来より広い DB 権限を持つ → Mitigation: Epic #286 の owner 判断として受容し、production write path が gateway 経由であることを回帰テストと spec で維持する
- [Risk] role provision script を削除しても Dockerfile・root deploy executor・DB helper の同期 payload manifest、foundation SQL、contract/self-test、docs に stale な参照が残る → Mitigation: 3箇所の固定 payload list を同一 cutover で更新し、`ReleaseDeployFoundationContractTest` と self-test で残存3-file contractを検証したうえで、対象識別子を active source/docs 全体から検索する
- [Risk] 約700行の role integration test を削除する際に MCP tool matrix または gateway persistence coverage まで失う → Mitigation: PR 1 で application-role regression を先行追加し、PR 2 ではその scenario が残ることを確認してから role-boundary fixture/assertion を削除する
- [Risk] PR 1 と PR 2 の間で application-role test と dedicated-role test が併存しテスト時間が増える → Mitigation: 併存期間を stacked 2 PR のレビュー期間に限定し、PR 2 で旧 role test を除去する
- [Risk] 他 database dependency を見落としてautocommit cleanupを始めると、`DROP ROLE` failure後にapplication DBだけ変更済みになる → Mitigation: `pg_shdepend` のcluster preflightと、`BEGIN`内で`REASSIGN OWNED`・`DROP OWNED`・`DROP ROLE`をまとめたdry-run/final transactionを使い、複数database fixtureでrollback atomicityを検証する
- [Risk] PR 2の3-file helper/markerのまま旧4-file imageへ戻すとcandidate verificationでrollbackが停止する → Mitigation: rollback SHAのexecutor/helper/SQL artifact setをexact installしてmarkerを再生成してから旧imageを起動する手順と双方向self-testを持つ
- [Risk] role を先に手動削除すると旧 image への rollback が失敗する → Mitigation: role cleanup を自動化せず、new image の安定確認後にownerが実施し、実施後のrollbackは旧artifact set復元に加えて旧provisionを再実行する
- [Trade-off] app role 統合後は DB ACL 単体で MCP の直接 write を禁止できない → submission gateway という application-level invariant を正本とし、single-owner paper 環境では重複 ACL を維持しない

## Migration Plan

1. PR 1 で application role を使う MCP tool matrix / submission gateway 回帰テストを additive に追加する。本番配線と dedicated-role test は維持する。
2. PR 2 で application role 配線、canary・compose、Dockerfile・root deploy executor・DB helper の同期 payload manifest、foundation SQL、contract/self-test、旧 role-boundary test、docs を一括で切り替える。DB schema migration はない。
3. PR 2 を通常の production deploy で起動し、MCP 起動と既存 tool call が application role で成立することを既存監査・health 経路で確認する。
4. disposable PostgreSQL の複数database fixtureで、cluster dependency preflight、transaction dry-runのrollback atomicity、final transactionのrole削除とownership保全を確認する。
5. 安定確認後、owner が PR 2 description のmigration noteに従い、cluster preflight → dry-run transaction → final transactionの順でproduction roleを削除する。
6. role削除前にrollbackする場合も、旧image起動前にrollback SHAのexecutor/helper/foundation/index/`mcp-role.sql`をexact配置し、旧helperで4-file markerを再生成する。role削除後はさらに旧provisionを同じSHAから再実行する。混在artifact setのままdeployしない。
7. PR 1 では OpenSpec change を archive せず、PR 2 完了後に一度だけ archive する。

## Open Questions

なし。
