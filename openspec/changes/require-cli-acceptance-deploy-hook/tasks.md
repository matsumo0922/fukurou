## 1. Signed forward hook contract

- [ ] 1.1 Add failing workflow, `deploy-contract-v1.json`, executor, candidate probe, and Kotlin preflight contract tests for the intent-dependent foundation/CLI hook sets, exact hook/operation equality, slug marker, and `CLI_AUTH_PREFLIGHT_V1` tuple.
- [ ] 1.2 Add `CLI_AUTH_PREFLIGHT_V1` to signed `FORWARD` bundles while keeping `AUTHORIZED_ROLLBACK` foundation-only, and reject any intent/hook mismatch before deployment mutation.
- [ ] 1.3 Extend candidate PID 1 and `DeploymentPreflightMain` to admit the exact CLI hook and signed allowlist without accepting unknown or partial hook sets.

## 2. Installed acceptance dispatch

- [ ] 2.1 Invoke the hash-verified installed harness with scrubbed override environment and exact `--cli-acceptance --runs 1 --reuse-image <digest>` before rollback capture/launch mutation, using a 600-second container budget and then restarting the existing 1,200-second forward deadline.
- [ ] 2.2 Admit both candidate hooks before the foundation harness, record CLI dispatch only after provider success plus exact marker, and cover missing auth, safe typed failure, TERM cleanup, mutation-zero failure, automatic recovery, and historical rollback compatibility without real credentials.

## 3. Documentation and validation

- [ ] 3.1 Update existing deploy and MCP runtime docs with the forward-only required hook, operator-owned canary auth, first-deploy bootstrap, safe failure evidence, and rollback behavior; grep README/docs for stale guidance.
- [ ] 3.2 Validate OpenSpec, shell/native/Kotlin contracts, deploy semantic/runtime/E2E selftests, real-harness exact-argv wiring under explicit selftest Docker, and separately record that real-provider operator evidence remains pending.
- [ ] 3.3 Run final `make test`, `make detekt`, and `make build` at one exact HEAD and record the command results and SHA in the PR description.
