## ADDED Requirements

### Requirement: Codex MCP invocation forwards the decision run invocation id
Issue #287 proposer-restoration invariant: Every Codex MCP server config MUST forward `FUKUROU_INVOCATION_ID` to the MCP subprocess via the rendered config's `env_vars`, so the MCP launcher's required-environment check does not fail closed.

#### Scenario: Production MCP config is rendered for a Codex invocation
- **WHEN** `OneShotLlmRunner` builds an `LlmMcpServerConfig` for a Codex-provider invocation
- **THEN** the rendered Codex config TOML's `env_vars` array contains `FUKUROU_INVOCATION_ID`, and the decision run's invocation id is present in the invocation's process environment
