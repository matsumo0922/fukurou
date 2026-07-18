## 1. Signed forward hook contract

- [ ] 1.1 Add failing workflow, `deploy-contract-v1.json`, executor, candidate probe, and Kotlin preflight contract tests for the exact ordered intent-dependent hook sets, ordered hook/operation equality, slug marker, and `CLI_AUTH_PREFLIGHT_V1` tuple; document that the C supervisor requires the new exact probe tuple while the JSON file is a static oracle.
- [ ] 1.2 Add `CLI_AUTH_PREFLIGHT_V1` to signed `FORWARD` bundles while keeping `AUTHORIZED_ROLLBACK` foundation-only, and reject any intent/hook mismatch before deployment mutation.
- [ ] 1.3 Extend candidate PID 1 and `DeploymentPreflightMain` to admit the exact CLI hook and signed allowlist without accepting unknown or partial hook sets.

## 2. Installed acceptance dispatch

- [ ] 2.1 Reduce the real harness and pinned selftest timeout to 600 seconds, invoke it with scrubbed override environment and exact `--cli-acceptance --runs 1 --reuse-image <digest>` before rollback capture/launch mutation, bound the host call, and then re-base the 1,200-second forward and 1,500-second recovery deadlines together.
- [ ] 2.2 Admit both candidate hooks before the foundation harness, record CLI dispatch only after provider success plus exact marker, and cover missing auth, safe typed failure, TERM cleanup, mutation-zero failure, automatic recovery, and historical rollback compatibility without real credentials.

## 3. Documentation and validation

- [ ] 3.1 Update existing deploy and MCP runtime docs with the forward-only required hook, 50-minute job / unchanged 20-minute mutation / 25-minute recovery budgets, operator-owned canary auth as a fresh-install/DR prerequisite, sudo env verification, safe failure evidence, and rollback behavior; grep README/docs for stale guidance.
- [ ] 3.2 Validate OpenSpec, shell/native/Kotlin contracts, deploy semantic/runtime/E2E selftests, real-harness exact-argv wiring under explicit selftest Docker, and separately record that real-provider operator evidence remains pending.
- [ ] 3.3 Run final `make test`, `make detekt`, and `make build` at one exact HEAD and record the command results and SHA in the PR description.

## 4. Review boundary

- [ ] 4.1 Keep the implementation at or below 15 files and 900 added/deleted lines excluding OpenSpec planning artifacts; stop and stage out work if the limit would weaken a normative scenario.
