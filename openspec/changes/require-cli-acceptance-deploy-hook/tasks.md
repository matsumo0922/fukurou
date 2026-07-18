## 1. Signed forward hook contract

- [ ] 1.1 Add failing workflow, executor, candidate probe, and Kotlin preflight contract tests for the intent-dependent foundation/CLI hook sets and exact `CLI_AUTH_PREFLIGHT_V1` tuple.
- [ ] 1.2 Add `CLI_AUTH_PREFLIGHT_V1` to signed `FORWARD` bundles while keeping `AUTHORIZED_ROLLBACK` foundation-only, and reject any intent/hook mismatch before deployment mutation.
- [ ] 1.3 Extend candidate PID 1 and `DeploymentPreflightMain` to admit the exact CLI hook and signed allowlist without accepting unknown or partial hook sets.

## 2. Installed acceptance dispatch

- [ ] 2.1 Invoke the hash-verified installed harness with `--cli-acceptance --runs 1 --reuse-image <exact digest>` after candidate CLI admission and before cutover, bounded by the existing deploy deadline.
- [ ] 2.2 Record CLI hook dispatch only after success and cover missing auth, safe typed failure, timeout cleanup, failure rollback, and automatic/historical rollback compatibility in contract and E2E fixtures without real credentials.

## 3. Documentation and validation

- [ ] 3.1 Update existing deploy and MCP runtime docs with the forward-only required hook, operator-owned canary auth, first-deploy bootstrap, safe failure evidence, and rollback behavior; grep README/docs for stale guidance.
- [ ] 3.2 Validate OpenSpec, shell/native/Kotlin contracts, deploy semantic/runtime/E2E selftests, and production call-path wiring.
- [ ] 3.3 Run final `make test`, `make detekt`, and `make build` at one exact HEAD and record the command results and SHA in the PR description.
