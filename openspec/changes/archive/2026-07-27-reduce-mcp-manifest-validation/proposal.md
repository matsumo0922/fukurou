## Why

MCP の起動 manifest 検証（`McpLaunchBootstrap.decode()`）は、LLM が manifest を改竄する攻撃者であるという前提で設計されている。single-owner 構成では書き手（`McpLaunchManifestWriter`）と読み手（`decode()`）が同一 codebase・同一 owner であり、この攻撃者モデルは成立しない。#282 の調査では、この検証群が「何が本質で何が儀式か」の判別を困難にし、誤診の一因になった。Epic #286 / Issue #289。

## What Changes

- `decode()` の検証を「運用ミス・バグの早期検出に診断価値があるもの」だけに絞る。
- 残す: manifest version 一致、`expiresAt` 失効、tool call limit の範囲と大小関係、`submissionSocketPath` 形式、DB password 存在（非空）、runtime config の存在と canonical 一致、phase の有効性。
- 撤去する: `toolSchemaHash` 照合（field ごと削除）、`allowedTools` の canonical set 照合（bootstrap は phase から catalog を直接引く）、`effectiveInvocationHash.length == 64` / `phaseManifestId` 非空の形式的 require、`invocationId == decisionRunId` の decode 側再検証（writer・auditor・gateway が既に強制）、manifest/password のサイズ上限、`systemPromptVersion` 非空の decode 側再検証（writer が既に強制）。
- `toolSchemaHash` field 削除に伴い `MCP_MANIFEST_VERSION` を 2 → 3 に上げる（残した version 検証で古い image と新しい manifest の組み合わせ事故を検出できるようにする）。
- 撤去した検証のテストを同じ PR で削除し、残した検証のテストを維持する。
- `docs/mcp-runtime.md` の manifest 検証の記述を現在形で更新する。

## Capabilities

### New Capabilities

なし。

### Modified Capabilities

- `llm-cli-invocation-contract`: #288 の launch-simplification requirement が「bootstrap の manifest 検証は変更せず維持する」と明記しているため、診断目的の検証だけを行う内容に改訂する。tool allowlist は manifest の値ではなく `McpToolContractCatalog` から phase で直接導出することを明記する。

## Impact

- `:mcp` の `McpLaunchBootstrap.decode()` と `FukurouMcpServerTest.kt`（`McpLaunchBootstrapPolicyTest` の reject 系テスト）。
- `:trading` の `McpLaunchManifest`（`toolSchemaHash` field 削除、version 3）と `McpLaunchManifestWriter`。
- `docs/mcp-runtime.md` の bootstrap 検証・allowlist 記述。
- gateway 側の `phaseManifestId` / `effectiveInvocationHash` 照合、writer 側の identity 検証、`McpToolContractCatalog` の allowlist 機構、audit / decision lineage field は変更しない。
- 想定 diff は数百行以内であり PR 分割は不要。
