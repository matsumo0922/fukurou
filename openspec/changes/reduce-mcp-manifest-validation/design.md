## Context

`McpLaunchBootstrap.decode()`（`mcp/src/main/kotlin/me/matsumo/fukurou/mcp/McpLaunchBootstrap.kt`）は manifest の decode 時に 13 の `require` を実行する。manifest の書き手は `trading` module の `McpLaunchManifestWriter` であり、読み手と同一 codebase・同一 owner・同一 UID で動く。書き手が `McpToolContractCatalog` から生成した値を読み手が同じ catalog で検算する構造（`toolSchemaHash`、`allowedTools`）や、writer / `LlmInvocationAuditor` / gateway が既に強制している不変条件の decode 側再検証（`invocationId == decisionRunId`、`systemPromptVersion` 非空）が混在し、#282 で「何が本質か」の判別を困難にした。

前提の #288（S1）は完了済みで、起動は argv manifest id + env 方式になっている。`openspec/specs/llm-cli-invocation-contract/spec.md` の #288 requirement には「The manifest validation performed at bootstrap is preserved unchanged (its simplification is out of scope)」とあり、本 change がその scope を引き継いで spec を改訂する。

## Goals / Non-Goals

**Goals:**

- `decode()` の検証を「運用ミス・バグの早期検出に診断価値があるもの」だけにする。
- 撤去した検証のテストを同じ PR で削除し、残した検証のテスト・MCP 起動〜tool call の回帰テストを維持する。
- `llm-cli-invocation-contract` spec と `docs/mcp-runtime.md` を現在の仕様として更新する。

**Non-Goals:**

- manifest ファイル自体の廃止。
- `McpToolContractCatalog` の phase 別 tool allowlist 機構の変更（LLM の行動制約として維持）。
- audit / decision lineage field（`promptHash`、`systemPromptVersion`、`phaseManifestId`、`effectiveInvocationHash` 等）の manifest からの削除。
- gateway（`LlmDecisionSubmissionGateway`）側の phase identity / invocation 照合の変更。
- writer（`McpLaunchManifestWriter.write()`）側の事前条件検証の変更。

## Decisions

### 1. 検証の分類

**残す（診断価値）:**

| 検証 | 理由 |
| --- | --- |
| `version == MCP_MANIFEST_VERSION` | 古い image と新しい manifest の組み合わせ事故の検出 |
| phase が有効な `LlmInvocationPhase` かつ catalog に tool がある | 未対応 phase での起動を早期に fail |
| `expiresAt` 未失効 | stale manifest での起動防止 |
| `totalToolCallLimit` / `actToolCallLimit` の範囲と大小関係 | config ミスの検出 |
| `submissionSocketPath` 非空・絶対・`.sock` | gateway 接続失敗の早期化 |
| DB password 非空（trim 後） | env 設定ミスの検出 |
| `TradingBotConfig.fromEnvironment` + `RuntimeConfigCatalog` の canonical 一致 | runtime snapshot の欠落・未知 key の検出（config ミス） |

**撤去（同一 codebase 内の自己不信）:**

| 検証 | 撤去理由 |
| --- | --- |
| `toolSchemaHash == canonicalSchemaHash(phase)` | 書き手と読み手が同じ catalog を参照。自分で書いた値の自己検算 |
| `allowedTools.toSet() == canonicalTools` | 同上。bootstrap は phase から catalog を直接引く（Decision 2） |
| `effectiveInvocationHash.length == 64` / `phaseManifestId.isNotBlank()` | 形式的 require。実効的な照合は gateway が担う |
| `invocationId == decisionRunId` | writer（`McpLaunchManifest.kt:124`）と `LlmInvocationAuditor.kt:241` が既に強制。decode 側は再検証 |
| `manifestBytes` / `passwordBytes` のサイズ上限 | fd-passing 時代の DoS 防御の名残（(ユーザー確認済み) 撤去） |
| `systemPromptVersion.isNotBlank()` | writer が既に強制（(ユーザー確認済み) 撤去） |

audit 経路の確認結果: `invocationId` / `decisionRunId` は decode 後に `DecisionRunContext` と `McpSubmissionGatewayBinding` へ別々に投影されるが、両者の一致は writer が生成時に保証しており、gateway が submission 時に `phaseManifestId` / `effectiveInvocationHash` / `invocationId` の束縛照合を行う。decode 時の同一性再検証は audit 上の追加情報を生まないため撤去する。

### 2. allowlist は manifest から読まず catalog から導出する

（ユーザー確認済み方針の帰結）`decode()` は `manifest.allowedTools` の照合をやめ、`McpBootstrapConfig.allowedTools` を `McpToolContractCatalog.toolsFor(phase)` から直接構築する。manifest の `allowedTools` field は renderer（CLI 側の tool policy 生成）が使うため field 自体は維持するが、MCP server の実効 allowlist の正本は catalog になる。これにより「manifest の値が canonical と一致するか」という照合自体が構造的に不要になる。

### 3. `toolSchemaHash` field を削除し MCP_MANIFEST_VERSION を 3 に上げる

（ユーザー確認済み）照合撤去後、`toolSchemaHash` は読み手のいない field になるため `McpLaunchManifest` から削除し、writer の hash 計算も消す。`MANIFEST_JSON` は `ignoreUnknownKeys = false` のため、field 削除は旧 manifest（version 2）と wire 非互換になる。`MCP_MANIFEST_VERSION` を 2 → 3 に上げ、残した version 検証で新旧の組み合わせ事故を明示的な失敗として検出する。manifest は per-run の短命ファイルであり、deploy を跨いで残存する version 2 manifest は expiry と version 検証の両方で拒否される。移行手順は不要。

代替案（field を残して検証だけ消す）は、誰も読まない hash が manifest に残り続け、将来の読者に「何かに使われている」と誤認させるため採用しない。

### 4. 残した検証に存在理由を1行で付ける

受け入れ条件に従い、残した各 `require` に非自明な場合のみ1行コメント（診断目的）を付けるか、メッセージ自体を診断的な文言にする。エラーメッセージから「descriptor」等の fd-passing 語彙を除去する。

### 5. テストの再編

- `McpLaunchBootstrapPolicyTest.bothPhasesRejectUnknownTamperedExpiredEmptyAndBudgetExceed` の reject リストから撤去した検証のケース（`decisionRunId = "different-decision-run"`、`allowedTools` 改変系）を削除し、残す検証のケース（version 不一致、期限切れ、limit 範囲外、runtime env 欠落/未知 key、socket path 不正）に整理する。`allowedTools` 改変ケースは「decode が reject する」から「decode 結果の allowlist が catalog 由来で改変の影響を受けない」の assertion に置き換える。
- MCP が正常 manifest で起動し tool call が成立する既存回帰テスト（`FukurouMcpServerTest` の bootstrap→server 経路）は維持する。
- `McpLaunchManifestTest` は `toolSchemaHash` field 削除の影響（serialization）だけ追随させる。

## Risks / Trade-offs

- [manifest version 3 への bump を writer / 検証 / canary fixture のどこかで更新し忘れる] → `MCP_MANIFEST_VERSION` 定数は 1 箇所であり writer / decode 双方が参照している。grep で literal `2` のハードコードがないことを確認する。
- [allowlist の正本を catalog に移した結果、manifest の `allowedTools` と MCP 実効 allowlist が乖離しても検出されない] → 乖離しても実効 allowlist は常に canonical であり、phase 別 tool 制限（LLM 行動制約）は catalog 側で維持される。renderer 側の allowlist は従来どおり `OneShotLlmRunner` が catalog から生成するため、乖離は fixture の手書き manifest でしか起きない。
- [撤去した検証が実は防いでいた事故を見逃す] → 撤去対象はすべて「writer が生成時に強制している」「gateway が submission 時に照合している」「読み手が存在しない」のいずれかに該当することをこの設計で確認済み。迷う項目が実装中に見つかった場合は撤去せず残し、PR description に列挙する。

## Migration Plan

1. `McpLaunchManifest` から `toolSchemaHash` を削除し、`MCP_MANIFEST_VERSION` を 3 にする。
2. `decode()` の撤去・整理と `McpBootstrapConfig.allowedTools` の catalog 導出化を同じ PR で行う。
3. テスト再編、`docs/mcp-runtime.md` 更新、spec delta 適用も同一 PR。
4. rollback は image を戻すだけでよい。version 2 image は version 3 manifest を version 検証で拒否し、manifest は per-run 生成のため旧 image 復帰後は version 2 manifest が再生成される。データ移行なし。

## Open Questions

なし。（ユーザー確認済み）2 点（field 削除 + version 3、サイズ上限・systemPromptVersion 再検証の撤去）以外の分類判断は issue の明記に従っており、実装中に迷う項目が出た場合は残して PR description に列挙する。
