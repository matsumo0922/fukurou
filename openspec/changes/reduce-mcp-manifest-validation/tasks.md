## 1. Manifest schema and writer

- [x] 1.1 Remove the `toolSchemaHash` field from `McpLaunchManifest`, drop the writer's schema hash computation, and bump `MCP_MANIFEST_VERSION` to 3; grep for hard-coded version literals and stale `toolSchemaHash` references.

## 2. Bootstrap validation reduction

- [x] 2.1 Reduce `McpLaunchBootstrap.decode()` to the diagnostic validations only (version, phase validity, expiry, tool call limit ranges, socket path shape, non-empty password, runtime config canonicality), removing schema hash equality, allowlist equality, identity re-verification, hash length/blank-shape requires, and manifest/password size caps; give each remaining require a diagnostic message or one-line reason.
- [x] 2.2 Derive `McpBootstrapConfig.allowedTools` from `McpToolContractCatalog.toolsFor(phase)` instead of the manifest payload, and remove fd-passing vocabulary ("descriptor") from remaining messages.

## 3. Tests

- [x] 3.1 Rework `McpLaunchBootstrapPolicyTest`: delete reject cases for removed validations, keep/extend reject cases for retained validations, and assert that a manifest with a divergent `allowedTools` payload still yields the catalog-canonical effective allowlist.
- [ ] 3.2 Keep the MCP startup → tool call regression path green and adapt `McpLaunchManifestTest` and any canary/fixture serialization to the version-3 manifest without `toolSchemaHash`.

## 4. Documentation and delivery

- [x] 4.1 Update `docs/mcp-runtime.md` manifest-validation and allowlist descriptions to the current diagnostic-only behavior in present tense, and grep docs/README for `toolSchemaHash` and stale validation claims.
- [ ] 4.2 Run `make test` / `make detekt`, and list in the PR description the retained validations with reasons, the removed validations, and any items kept because classification was uncertain.
