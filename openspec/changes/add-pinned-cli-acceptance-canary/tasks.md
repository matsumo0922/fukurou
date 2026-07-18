## 1. Acceptance runtime

- [x] 1.1 Add failing tests for the four-phase matrix, pinned CLI/configured model/output validation, Claude observed model, Codex unavailable observation, canonical no-tool/MCP policies, safe failures, and bounded repetition.
- [x] 1.2 Implement the data-free fixture MCP server and app-UID `CliAcceptanceCanaryMain` with production renderer, direct pinned CLI templates, process runner, parser, and guaranteed cleanup.

## 2. Exact-image harness

- [x] 2.1 Add immutable-digest `--qualification --runs 3` and `--cli-acceptance --runs 1` modes to the existing harness; qualification resolves once and runs foundation plus acceptance on the same image, using only a read-only dedicated `llm-canary-auth` volume, private tmpfs, bounded resources, and no production mounts or networks.
- [x] 2.2 Add offline fixture coverage for missing/expired auth, mutable mount, unsupported repetition, phase/tool/schema/model/timeout failures, safe logging, source isolation, and lifecycle cleanup.

## 3. Documentation and validation

- [x] 3.1 Update existing deploy and LLM runtime documentation with dedicated canary login, exact-image one/three-run commands, evidence composition, and the intentionally incomplete deploy hook; grep README/docs for stale CLI smoke guidance.
- [x] 3.2 Add the full Issue #189 closure matrix to the PR description source, separating prior evidence, foundation evidence, this change, operator evidence, and the remaining deploy hook.
- [x] 3.3 Validate OpenSpec and run targeted Kotlin, script contract, safe-output, and exact-image offline tests.
- [x] 3.4 Check that the implementation code/test/script/docs diff excludes pre-implementation OpenSpec artifacts and remains at or below the 1,100-line hard stop; STOP and stage out work if it exceeds the limit.
- [ ] 3.5 Build the final immutable image and use one qualification invocation to run foundation once plus the four-phase real-provider matrix three consecutive times, recording only the single safe digest-bound result.
- [x] 3.6 Run final `make test`, `make detekt`, and `make build` at one exact HEAD.
