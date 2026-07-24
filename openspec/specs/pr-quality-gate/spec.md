# pr-quality-gate Specification

## Purpose
Pull request ごとに JVM test と静的解析を必須化し、連続 push の古い実行を取り消す quality gate を定義する。
## Requirements
### Requirement: Pull request triggers JVM quality checks
The repository MUST run a `pull_request`-triggered workflow that executes `make test` and `make detekt` against the PR head commit on a GitHub-hosted Linux runner.

#### Scenario: PR quality checks pass
- **WHEN** a pull request's `make test` and `make detekt` both succeed
- **THEN** the workflow reports success and no code changes are required before review

#### Scenario: PR quality checks fail
- **WHEN** `make test` or `make detekt` fails for a pull request
- **THEN** the workflow reports failure and the PR check is red

### Requirement: Concurrent pushes to the same PR are serialized with cancellation
The workflow MUST use a concurrency group scoped to the pull request and cancel an in-progress run when a newer push arrives on the same PR.

#### Scenario: Second push cancels the first run
- **WHEN** a pull request receives a second push while its first push's quality run is still in progress
- **THEN** the first run is cancelled and only the run for the latest push completes

### Requirement: Main branch requires a passing quality check via pull request
The repository's `main` branch MUST be protected such that direct pushes are rejected and merges require this workflow's job to have succeeded. This protection MUST apply to repository admins as well (`enforce_admins: true`, or an equivalent ruleset with no bypass actors) — the single owner's own admin privileges MUST NOT be usable to skip the required check.

#### Scenario: Direct push to main is rejected
- **WHEN** a contributor attempts to push a commit directly to `main` without going through a pull request
- **THEN** GitHub rejects the push due to branch protection

#### Scenario: Admin direct push is also rejected
- **WHEN** the repository owner, who holds admin privileges, attempts to push directly to `main`
- **THEN** GitHub rejects the push the same way, because admin enforcement is enabled and no bypass actor is configured

#### Scenario: PR merges only after the quality check succeeds
- **WHEN** a pull request's quality check job has not succeeded
- **THEN** the PR cannot be merged into `main`
