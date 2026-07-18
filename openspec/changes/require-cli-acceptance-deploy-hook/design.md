## Context

PR #257 introduced an exact-image acceptance harness that exercises the four production CLI phases with a dedicated read-only `llm-canary-auth` volume. The deploy capability catalog already reserves `CLI_AUTH_PREFLIGHT_V1`, but signed bundles require only `FOUNDATION_PREFLIGHT_V1`; candidate PID 1 and `DeploymentPreflightMain` therefore accept only the foundation tuple. The root executor runs required hooks after rollback capture, launch disable, and drain, but before production compose cutover.

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

Candidate PID 1 will accept the exact CLI and foundation probe tuples. `DeploymentPreflightMain` will select the marker from the requested slug and verify that the signed token allowlist contains the requested supported hook. It will accept exactly the ordered hook sets used by forward and rollback bundles, rejecting unknown, duplicate, reordered, or partial sets.

### Require CLI acceptance only for forward intent

（ユーザー確認済み）The workflow adds `CLI_AUTH_PREFLIGHT_V1` to `requiredHooks` and operations only when `deployIntent=FORWARD`. `AUTHORIZED_ROLLBACK` keeps the foundation-only bundle. This keeps the new gate permanent for risk-increasing cutover while preserving recovery to an older image that cannot understand the new tuple.

The executor validates the same intent-dependent invariant instead of merely accepting any catalog operation. A forward bundle missing CLI acceptance and a rollback bundle that claims it are both rejected before Docker pull or durable mutation.

### Split candidate admission from real-provider execution

（agent 仮決め）Each required hook first runs inside the candidate PID 1 canary with the signed one-shot token and exact catalog/image binding. After `cli-auth` admission succeeds, the root executor invokes the already hash-verified installed foundation harness as:

```sh
mcp-credential-isolation-check --cli-acceptance --runs 1 \
  --reuse-image "${IMAGE_REPOSITORY}@${CANDIDATE_DIGEST}"
```

The candidate canary intentionally has neither auth volume nor provider network access, so the host-installed harness owns the isolated Docker invocation. Success is recorded in the dispatch ledger only after both layers succeed.

### Keep credentials operator-managed and failure output safe

（ユーザー確認済み）Deploy does not create or mutate `llm-canary-auth` and never falls back to production `llm-auth`. Missing/expired auth is a deploy failure. Existing harness safe-marker validation remains the only provider result forwarded; raw stdout/stderr stays in the root-local 0700 artifact directory referenced by path.

### Reuse the existing deploy deadline and rollback transaction

（agent 仮決め）The executor wraps acceptance in a bounded timeout below the existing absolute forward deadline. Timeout, auth failure, or typed incompatibility occurs after rollback capture and launch drain but before `deploy_compose`; the existing error trap restores the previous image and launch state. No second rollback system or retry loop is added.

## Risks / Trade-offs

- [Dedicated auth expires and blocks routine deploys] → This is the intended fail-closed behavior; docs retain explicit operator login/refresh and a one-run smoke command.
- [Provider latency consumes the forward deadline] → Bound the harness invocation, keep the global watchdog authoritative, and test timeout cleanup plus rollback.
- [Forward/rollback hook sets drift across workflow, executor, candidate, and tests] → Encode the two exact intent-dependent sets and exercise both in contract and E2E fixtures.
- [Installed executor/harness hash changes block the first deploy] → Follow the existing root-owned bootstrap/install procedure from the same reviewed SHA before automatic deploy; do not weaken signed hash checks.
- [Real credentials make CI nondeterministic] → CI uses the existing explicit selftest/fake harness seam; only the operator smoke uses `llm-canary-auth` and real providers.

## Migration Plan

1. Build and review the exact PR HEAD; run deploy contract, runtime, E2E, Kotlin, OpenSpec, and repository validation.
2. Before the first forward deploy, freeze concurrent deploys and install the reviewed executor plus the already-required harness/catalog artifact set from the same SHA using the existing NAS bootstrap procedure.
3. Confirm `llm-canary-auth` exists and run the documented one-run operator smoke against the exact candidate digest.
4. Merge and deploy. The signed forward bundle requires both foundation and CLI auth hooks; failure restores the previous deployment.
5. Rollback uses the previous image/compose rollback bundle automatically. An explicit historical `AUTHORIZED_ROLLBACK` uses the foundation-only hook set and does not claim current provider qualification.

## Open Questions

なし。credential lifecycle remains operator-owned, and the forward-only gate / rollback exception follows the previously confirmed safety direction.
