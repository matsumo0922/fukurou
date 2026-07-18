## Context

PR #257 introduced an exact-image acceptance harness that exercises the four production CLI phases with a dedicated read-only `llm-canary-auth` volume. The deploy capability catalog already reserves `CLI_AUTH_PREFLIGHT_V1`, but signed bundles require only `FOUNDATION_PREFLIGHT_V1`; candidate PID 1 and `DeploymentPreflightMain` therefore accept only the foundation tuple. The root executor currently runs required hooks after rollback capture, launch disable, and drain, but before production compose cutover. The CLI provider gate must run earlier because credential/provider failure is independent of candidate safety and must not leave a `ROLL_FORWARD_ONLY` deployment in manual recovery.

Issue #189 requires this acceptance to become a permanent deploy gate. The existing signed bundle, candidate token, installed-artifact hash, dispatch ledger, and rollback machinery remain the authority boundaries.

## Goals / Non-Goals

**Goals:**

- Require one real-provider CLI acceptance matrix for every `FORWARD` deploy before candidate cutover.
- Bind candidate support, token authorization, installed harness execution, and dispatch evidence to `CLI_AUTH_PREFLIGHT_V1` and the exact image digest.
- Fail closed without exposing provider raw output or production credentials.
- Preserve automatic recovery and explicit historical rollback.

**Non-Goals:**

- Running the three-repetition merge qualification during every deploy.
- Creating, refreshing, or logging into `llm-canary-auth` automatically.
- Changing production `llm-auth`, DB schema, trading behavior, API contracts, network topology, or Issue #154 activation.
- Claiming that an `AUTHORIZED_ROLLBACK` image has passed the new provider gate.

## Decisions

### Reuse the reserved typed hook

（agent 仮決め）Use the existing cataloged `CLI_AUTH_PREFLIGHT_V1` / `cli-auth` tuple instead of adding a new capability, operation ID, or catalog version. PR #257 explicitly left this connection for the deploy-hook change, and the semantic scope is CLI authentication plus output/tool compatibility.

Candidate PID 1 will accept the exact CLI and foundation probe tuples. `DeploymentPreflightMain` will map the requested slug to its hook ID and exact success marker, verify that the signed token allowlist contains the requested hook, and accept exactly the ordered hook sets used by forward and rollback bundles. The executor captures candidate output and requires the slug-specific safe marker, rejecting unknown, duplicate, reordered, partial, or mismatched sets.

### Require CLI acceptance only for forward intent

（ユーザー確認済み）The workflow adds `CLI_AUTH_PREFLIGHT_V1` to `requiredHooks` and operations only when `deployIntent=FORWARD`. `AUTHORIZED_ROLLBACK` keeps the foundation-only bundle. This keeps the new gate permanent for risk-increasing cutover while preserving recovery to an older image that cannot understand the new tuple.

The executor validates the same intent-dependent invariant instead of merely accepting any catalog operation. It also requires exact equality between `requiredHooks` and the `SMOKE_HOOK_V1` operation IDs. A forward bundle missing CLI acceptance and a rollback bundle that claims it are both rejected before Docker pull or durable mutation. `scripts/deploy/deploy-contract-v1.json`, workflow generation, executor validation, fixtures, and Kotlin contract tests are all contract surfaces.

All forward deploys are gated, including incident roll-forwards and same-SHA redeploys. No new bypass is introduced. This is the requested permanent hook contract; risk reduction remains available through existing launch disable/manual halt controls and signed `AUTHORIZED_ROLLBACK`, not through an unqualified new image.

### Split candidate admission from real-provider execution

（agent 仮決め）After exact digest/tuple probing but before rollback-state capture or launch disable, the root executor invokes the already hash-verified installed foundation harness as:

```sh
mcp-credential-isolation-check --cli-acceptance --runs 1 \
  --reuse-image "${IMAGE_REPOSITORY}@${CANDIDATE_DIGEST}"
```

The candidate canary intentionally has neither auth volume nor provider network access, so the host-installed harness owns the isolated Docker invocation. The executor scrubs all canary selftest/override environment before calling it. Later, after rollback capture and drain, the signed candidate canary admits `cli-auth`; the executor asserts the exact safe marker and records dispatch only when both the earlier provider result and candidate admission succeeded.

### Keep credentials operator-managed and failure output safe

（ユーザー確認済み）Deploy does not create or mutate `llm-canary-auth` and never falls back to production `llm-auth`. Missing/expired auth is a deploy failure. Existing harness safe-marker validation remains the only provider result forwarded; raw stdout/stderr stays in the root-local 0700 artifact directory referenced by path.

### Give acceptance a separate pre-mutation budget

（agent 仮決め）The acceptance container's existing 2,400-second timeout is reduced to 600 seconds, above the four 120-second phase budgets but below the deploy job timeout. Acceptance runs after unfinished-deployment recovery, intent admission, and exact candidate operation probing, but before rollback capture and launch mutation. After it succeeds, the executor restarts the existing 1,200-second forward deadline for compose validation, journal/mutation, foundation preflight, cutover, and recovery. Provider failure therefore leaves current production untouched and does not consume the launch-disabled window.

The signed candidate token is issued only for the later candidate hook loop. `CLI_AUTH_PREFLIGHT_V1` is ordered before `FOUNDATION_PREFLIGHT_V1`; both candidate admissions complete before the foundation harness is run, so the 900-second token lifetime never contains either real-provider acceptance or the 900-second foundation harness.

## Risks / Trade-offs

- [Dedicated auth expires and blocks routine deploys] → This is the intended fail-closed behavior; docs retain explicit operator login/refresh and a one-run smoke command.
- [Provider latency delays deploy admission] → Give acceptance a separate 600-second pre-mutation budget; reset the existing mutation/recovery deadline only after success and test TERM cleanup with the real harness selftest seam.
- [Forward/rollback hook sets drift across workflow, executor, candidate, and tests] → Encode the two exact intent-dependent sets and exercise both in contract and E2E fixtures.
- [Installed executor/harness hash changes block the first deploy] → Follow the existing root-owned bootstrap/install procedure from the same reviewed SHA before automatic deploy; do not weaken signed hash checks.
- [Real credentials make CI nondeterministic] → Contract tests execute the real harness with explicit selftest Docker and assert exact executor argv; E2E uses the existing fake executable for failure transaction coverage. Only the operator smoke uses `llm-canary-auth` and real providers.
- [Provider outage blocks a new forward hotfix] → This is an explicit consequence of the permanent forward gate; use existing launch disable/manual halt and signed historical rollback for risk reduction.

## Migration Plan

1. Build and review the exact PR HEAD; run deploy contract, runtime, E2E, Kotlin, OpenSpec, and repository validation.
2. Before the first forward deploy, freeze concurrent deploys and install the reviewed executor plus the already-required harness/catalog artifact set from the same SHA using the existing NAS bootstrap procedure.
3. Freeze concurrent deploy/operator login activity, confirm `llm-canary-auth` exists, and run the documented one-run operator smoke against the exact candidate digest.
4. Merge and deploy. The signed forward bundle requires both foundation and CLI auth hooks; failure restores the previous deployment.
5. Rollback uses the previous image/compose rollback bundle automatically. An explicit historical `AUTHORIZED_ROLLBACK` uses the foundation-only hook set and does not claim current provider qualification.

## Open Questions

なし。credential lifecycle remains operator-owned; every forward deploy is intentionally gated without a new bypass, while existing launch disable and signed rollback remain the recovery boundary.
