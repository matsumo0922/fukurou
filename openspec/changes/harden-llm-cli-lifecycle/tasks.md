## 1. Provider invocation and activation contract

- [ ] 1.1 Reconnect the code-owned release barrier to the daemon pre-filter gate and prove configured `true` starts no pre-filter child while the barrier is closed
- [ ] 1.2 Add required phase-owned `ToolPolicy` with no default and migrate pre-filter, proposer, falsifier, Reflection, report, manual, and test callers
- [ ] 1.3 Remove Claude no-MCP `--bare` while retaining copied per-run auth, strict empty MCP config, explicit empty tools, secret stripping, and explicit pre-filter Haiku model
- [ ] 1.4 Add pinned-version Claude result and Codex JSONL adapters that separate semantic response, usage, configured/observed model identity, safe provider detail, and schema validation
- [ ] 1.5 Add stable categories for authentication, rate/session limit, quota, output contract, timeout, process exit, cleanup, and unknown provider failure without persisting Codex raw output or secrets
- [ ] 1.6 Remove Codex session-directory model attribution scans and retain configured identity plus provider-observed identity when available
- [ ] 1.7 Add rendering, auth absence, empty/missing tool policy, schema drift, failure classification, model attribution, barrier, and no-session-scan tests
- [ ] 1.8 Update CLI setup, daemon, MCP runtime, design, and failure runbook documentation and grep `--bare`, barrier, auth heuristic, and session attribution references
- [ ] 1.9 Run provider/daemon targeted tests, `make test`, `make detekt`, `make build`, CLI fixtures, diff check, and verify this PR stays below the 1,300-line hard stop
