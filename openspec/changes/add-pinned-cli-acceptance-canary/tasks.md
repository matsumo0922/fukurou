## 1. Acceptance runtime

- [ ] 1.1 Add failing tests for the four-phase matrix, pinned model/output validation, canonical no-tool/MCP policies, safe failures, and bounded repetition.
- [ ] 1.2 Implement the data-free fixture MCP server and `CliAcceptanceCanaryMain` with production renderer, fixed launcher, process runner, parser, and guaranteed cleanup.
- [ ] 1.3 Add the PID 1 acceptance-only supervisor mode and exact process/timeout/termination contract tests.

## 2. Exact-image harness

- [ ] 2.1 Add an exact-image harness that accepts only `--runs 1|3`, mounts the existing auth volume read-only, starts no DB or application, and verifies source immutability and security inventory.
- [ ] 2.2 Add offline fixture coverage for missing auth, mutable mount, unsupported repetition, phase/tool/schema/model/timeout failures, safe logging, and lifecycle cleanup.

## 3. Signed deploy gate

- [ ] 3.1 Extend candidate preflight token verification and capability catalog for ordered `FOUNDATION_PREFLIGHT_V1` and `CLI_AUTH_PREFLIGHT_V1` hooks.
- [ ] 3.2 Bind the CLI harness hash into the signed workflow bundle and installed executor contract, then run one acceptance matrix after foundation preflight and before production mutation.
- [ ] 3.3 Extend deploy contract/e2e selftests for omitted, reordered, mismatched, and failing CLI auth hooks plus successful dispatch receipt.

## 4. Documentation and validation

- [ ] 4.1 Update existing deploy and LLM runtime documentation, then grep README/docs for stale CLI smoke and auth canary guidance.
- [ ] 4.2 Validate OpenSpec and run targeted Kotlin, supervisor, script, deploy contract, and exact-image offline tests.
- [ ] 4.3 Build the final exact image, run the four-phase real-provider matrix three consecutive times, and record only safe acceptance evidence.
- [ ] 4.4 Run final `make test`, `make detekt`, and `make build` at one exact HEAD.
