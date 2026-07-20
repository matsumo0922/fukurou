# Issue #192 PR-2 pre-verification safety evidence

PR-2 contains no production mutation. This artifact records only repository evidence. Production baseline activation, NAS flag setup, deploy observation, both arms, and cleanup are `UNOBSERVED` and belong to PR-3.

## Scope split

| Item | Result |
|---|---|
| PR-2 | target-order / pending-cancel safety fix, tests, OpenSpec, PR-3 HANDOFF |
| PR-3 | reviewed production deploy, baseline activation, two owner-gated arms, terminal evidence, temporary seam cleanup |
| production mutation during PR-2 | none |

## Independent falsification disposition

| Finding | Disposition |
|---|---|
| PENDING_CANCEL omitted from controller final preflight | accepted; controller now requires zero and proving test asserts audit/disconnect zero |
| owner-approved order can be replaced under count-only preflight | accepted; request/audit/final snapshot bind UUID, expiry, and minimum TTL |
| application spec overclaimed NAS/GitHub/timer authority | accepted as contract correction; bounded operator check owns cross-system state, later drift is `INVALID` without retry |
| corrected controller was not deployable in the original post-arm cleanup PR | accepted; PR-2 is now the merge/deploy prerequisite and PR-3 owns arms/cleanup |

Claude Opus 4.8 was unavailable due rate limit. The user selected fresh `gpt-5.5` at xhigh effort for independent falsification. No Claude session was resumed.

## Repository implementation

| Item | Result |
|---|---|
| changed production files | `Issue192WsFaultController.kt`, `Issue192WsFaultSeam.kt` |
| changed test file | `Issue192WsFaultSeamTest.kt` |
| target request parsing | UUID + ISO-8601 expiry + TTL `1..86400` seconds |
| target final gate | same UUID, `OPEN`, BUY, position-unlinked, exact expiry, sufficient TTL |
| aggregate final gate | `OPEN` resting BUY >= 1, `PENDING_CANCEL` resting BUY = 0, open positions = 0 |
| rejection side effects | requested audit 0, executed audit 0, disconnect 0 |
| fixed one-shot / gap impact | unchanged |

## Validation

| Command | Result |
|---|---|
| `./gradlew :fukurou:test --tests me.matsumo.fukurou.Issue192WsFaultSeamTest` under shared validation lease | PASS |
| `./gradlew :fukurou:detekt` under shared validation lease | PASS |
| `git diff --check` | PASS |
| standard `make test` full validation | FAIL twice from non-deterministic PostgreSQL SSL/connect/socket/lock timeouts in unrelated tests; failed test set changed between runs |
| failed-test targeted reruns | PASS for the first-run 1 fukurou + 3 trading failures |
| serialized `./gradlew test --max-workers=1` | fukurou PASS; trading 999 tests with 2 unrelated Hikari connection failures |
| admission health isolation regression tasks | PASS |
| `make detekt` all modules | PASS |
| CI full validation | UNOBSERVED |
| final clean-context falsification | PASS / blocking 0 |

Final fresh `gpt-5.5` / `xhigh` falsification after the PR-2/PR-3 split returned `PASS` with `blocking_count: 0`. Its only non-blocking finding requested exact active-work observation in the PR-3 HANDOFF; the bounded read-only SQL was added there.

## Production-dependent HANDOFF

The detailed secret-free pre-deploy audit, canonical `1000000 JPY` activation gate, owner-performed NAS flag step, `ROLL_FORWARD_ONLY` decision, two arm templates, and finite cleanup inventory are preserved in `pr3-production-handoff.md`. They are not PR-2 success claims.

Draft delivery: [PR #272](https://github.com/matsumo0922/fukurou/pull/272). The PR remains unmerged and production remains unchanged.

Fresh `gpt-5.5` / `xhigh` final review returned `APPROVED` with must-fix 0. One accepted HANDOFF wording fix was implemented by a fresh `gpt-5.6-sol` / medium worker and revalidated. GitHub `Deploy contract and PostgreSQL 16` and GitGuardian checks passed on the reviewed code HEAD; the documentation-only review-fix HEAD is rechecked before final HANDOFF.
