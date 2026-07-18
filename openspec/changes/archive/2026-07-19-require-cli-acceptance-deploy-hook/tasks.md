## 1. Signed forward hook contract

- [x] 1.1 Add failing workflow, `deploy-contract-v1.json`, executor, candidate probe, and Kotlin preflight contract tests for the exact ordered intent-dependent hook sets, ordered hook/operation equality, slug marker, and `CLI_AUTH_PREFLIGHT_V1` tuple; document that the C supervisor requires the new exact probe tuple while the JSON file is a static oracle.
- [x] 1.2 Add `CLI_AUTH_PREFLIGHT_V1` to signed `FORWARD` bundles while keeping `AUTHORIZED_ROLLBACK` foundation-only, and reject any intent/hook mismatch before deployment mutation.
- [x] 1.3 Extend candidate PID 1 and `DeploymentPreflightMain` to admit the exact CLI hook and signed allowlist, run the candidate compose non-interactively, and require the exact slug-specific marker without accepting unknown, partial, or command-drifted hook sets.

## 2. Installed acceptance dispatch

- [x] 2.1 Make the real harness and pinned selftest timeout repetition-dependent (720 seconds per repetition), invoke one-run acceptance with scrubbed override environment and exact `--cli-acceptance --runs 1 --reuse-image <digest>` after production-compose validation and before rollback capture, re-arm a 750-second watchdog at stage entry, then re-base/re-arm/check the 1,200-second forward and 1,500-second recovery deadlines together after success.
- [x] 2.2 Add `selftest=false` to the deploy acceptance success marker, admit both candidate hooks before the foundation harness, record CLI dispatch only after provider success plus exact marker, and cover missing auth, safe typed failure, TERM cleanup, mutation-zero failure, automatic recovery, and historical rollback compatibility without real credentials.

## 3. Documentation and validation

- [x] 3.1 Update `.github/workflows/deploy.yml` to a 60-minute job timeout and update existing deploy/MCP docs at the deadline/common-origin sections with the forward-only hook, separate 750-second admission budget, unchanged 20-minute mutation / 25-minute recovery budgets, operator-owned canary auth as a fresh-install/DR prerequisite, sudo env verification, no concurrent operator smoke/login, safe failure evidence, quota/load coupling, and rollback behavior; grep README/docs for stale guidance.
- [x] 3.2 Validate OpenSpec, shell/native/Kotlin contracts, deploy semantic/runtime/E2E selftests, real-harness exact-argv wiring under explicit selftest Docker, and separately record that real-provider operator evidence remains pending.
- [x] 3.3 Run final `make test`, `make detekt`, and `make build` at one exact HEAD and record the command results and SHA in the PR description.

## 4. Review boundary

- [x] 4.1 Keep the implementation at or below 15 files and 900 added/deleted lines excluding OpenSpec planning artifacts; stop and report for human boundary review if the limit would be exceeded.
