## 1. Workflow contract

- [x] 1.1 Add a failing file-level contract test for resolved-SHA/quality-required outputs, exact-ref quality/build checkouts, test/detekt/clean-tree commands, least privilege, and closed build dependencies.

## 2. Quality gate implementation

- [x] 2.1 Split target resolution into a GitHub-hosted read-only job that exports the normalized main-reachable SHA and whether the target requires quality.
- [x] 2.2 Add a GitHub-hosted read-only quality job that verifies exact HEAD, configures Java/Gradle cache, runs `make test` and `make detekt`, and rejects tracked auto-corrections.
- [x] 2.3 Make image build verify exact HEAD and depend on resolution plus either successful required quality or an explicitly historical manual target with skipped quality, while preserving existing tags, bundle, permissions, artifact, deploy serialization, and root executor invocation.

## 3. Documentation and validation

- [x] 3.1 Update `docs/deploy.md` and grep README/docs for stale build/deploy descriptions.
- [x] 3.2 Validate the OpenSpec change and run targeted workflow contract tests.
- [x] 3.3 Run final `make test`, `make detekt`, and `make build` at one exact HEAD and record evidence for the PR.
