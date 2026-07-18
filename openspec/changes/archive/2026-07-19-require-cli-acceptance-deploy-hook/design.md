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

The executor validates the same intent-dependent invariant instead of merely accepting any catalog operation. It requires ordered equality between `requiredHooks` and the `SMOKE_HOOK_V1` operation IDs: forward is exactly `[CLI_AUTH_PREFLIGHT_V1, FOUNDATION_PREFLIGHT_V1]`; authorized rollback is exactly `[FOUNDATION_PREFLIGHT_V1]`. The workflow arrays, executor plan, signed token, candidate calls, and fixtures preserve this order. A forward bundle missing CLI acceptance and a rollback bundle that claims it are both rejected before Docker pull or durable mutation.

`scripts/deploy/deploy-contract-v1.json` is a static test oracle, not an executor input. It retains the legacy `requiredHooks` baseline and gains an additive `requiredHooksByIntent` map for the two exact sets. Kotlin and shell contract tests compare this oracle with workflow/executor fixtures; production enforcement remains the signed bundle plus executor validation.

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

（agent 仮決め）The shared acceptance container timeout becomes repetition-dependent: 720 seconds for one-run deploy smoke and 2,160 seconds for three-run qualification. Each repetition receives the four 120-second phase limits plus 240 seconds for JVM/CLI/fixture startup and cleanup, so the deploy limit does not make qualification impossible.

Acceptance runs after unfinished-deployment recovery, intent admission, exact candidate operation probing, and deterministic production-compose validation, but before rollback capture and launch mutation. At acceptance-stage entry the executor stops the old watchdog, creates a dedicated 750-second host deadline, and re-arms the watchdog; the 30-second margin lets the container timeout run its cleanup trap before the host deadline. After success it stops that watchdog, re-bases both existing deadlines to the same new origin—1,200 seconds for forward work and 1,500 seconds for recovery—and re-arms plus immediately checks the watchdog. Provider failure therefore leaves current production untouched and does not consume the launch-disabled window.

（agent 仮決め）The GitHub deploy job timeout increases from 35 to 60 minutes. The structural path is pre-acceptance work bounded by 1,200 seconds + acceptance/cleanup 750 seconds + recovery 1,500 seconds = 3,450 seconds, leaving 150 seconds before the 3,600-second runner kill. The production mutation/recovery budgets themselves remain 20/25 minutes; only the outer runner ceiling changes.

The signed candidate token is issued only for the later candidate hook loop. `CLI_AUTH_PREFLIGHT_V1` is ordered before `FOUNDATION_PREFLIGHT_V1`; both candidate admissions complete before the foundation harness is run. The only token invariant is that both short candidate admissions complete within its 900-second lifetime; the later foundation harness does not consume token-authorized work.

## Risks / Trade-offs

- [Dedicated auth expires and blocks routine deploys] → This is the intended fail-closed behavior; docs retain explicit operator login/refresh and a one-run smoke command.
- [Provider latency delays deploy admission] → Give one-run acceptance a separate 720/750-second container/host pre-mutation budget; re-arm the watchdog at stage entry and again for mutation/recovery, then test TERM cleanup with the real harness selftest seam.
- [Forward/rollback hook sets drift across workflow, executor, candidate, and tests] → Encode the two exact intent-dependent sets and exercise both in contract and E2E fixtures.
- [Installed executor/harness hash changes block the first deploy] → Follow the existing root-owned bootstrap/install procedure from the same reviewed SHA before automatic deploy; do not weaken signed hash checks.
- [Real credentials make CI nondeterministic] → Contract tests execute the real harness with explicit selftest Docker and assert exact executor argv; E2E uses the existing fake executable for failure transaction coverage. Only the operator smoke uses `llm-canary-auth` and real providers.
- [Provider outage blocks a new forward hotfix] → This is an explicit consequence of the permanent forward gate; use existing launch disable/manual halt and signed historical rollback for risk reduction.
- [Fresh install or disaster recovery has no older image to authorize] → Treat provisioned `llm-canary-auth` as a NAS bootstrap prerequisite. If it is unavailable, no forward deploy occurs; do not claim rollback or runtime halt as available before a current runtime exists.
- [Canary and production accounts may share provider quota] → Credential volumes are isolated but account/quota isolation is not proven; document the coupling and treat rate/session/quota failure as a safe deploy stop.
- [Pre-mutation canary adds CPU/network load beside production] → Keep the existing 2 CPU / 2 GiB limits, run one matrix only, and document that it coexists with the current runtime before drain.

## Migration Plan

1. Build and review the exact PR HEAD; run deploy contract, runtime, E2E, Kotlin, OpenSpec, and repository validation.
2. Before fresh install, DR, or the first forward deploy, freeze concurrent deploys and install the reviewed executor plus the already-required harness/catalog artifact set from the same SHA using the existing NAS bootstrap procedure.
3. Provision `llm-canary-auth` as a required NAS bootstrap artifact, freeze concurrent deploy/operator login/smoke activity, verify sudo does not retain canary selftest/override environment, and run the documented one-run operator smoke against the exact candidate digest.
4. Merge and deploy. The signed forward bundle requires both foundation and CLI auth hooks; failure restores the previous deployment.
5. Rollback uses the previous image/compose rollback bundle automatically. An explicit historical `AUTHORIZED_ROLLBACK` uses the foundation-only hook set and does not claim current provider qualification.

## Open Questions

なし。credential lifecycle remains operator-owned; every forward deploy is intentionally gated without a new bypass, while existing launch disable and signed rollback remain the recovery boundary.
