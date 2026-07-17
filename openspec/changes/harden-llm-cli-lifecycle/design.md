## Context

Issue #189 is a prerequisite for Issue #154, but the current repository is not the baseline described by the issue's first investigation. The current `main` already has pinned CLI versions, signed deploy bundles, a PID 1 runtime supervisor, fixed-purpose launchers, per-run homes on tmpfs, credential isolation, cleanup quarantine, and a production-like canary. This design extends those boundaries and does not replace them.

The remaining gaps are concrete:

- the daemon pre-filter gate reads `daemon.preFilterEnabled` directly even though a closed `LlmLaunchReleaseBarrier` still exists;
- no-MCP Claude rendering copies auth and then passes `--bare`;
- process-group cleanup is stronger on Linux, but fallback descendant enumeration can still abort before parent termination;
- one `RUNNER_PHASE_COMPLETED` payload conflates semantic, process, and cleanup outcomes;
- provider failure detection remains an English substring heuristic;
- Codex model attribution scans session JSONL files after the run;
- per-run homes are already deleted through tmpfs cleanup/quarantine, but the retention SLO and its acceptance evidence are not explicit;
- `/evaluation/costs` preserves unknown monetary cost correctly but does not compute versioned Codex list-price estimates or distinguish all monetary concepts;
- deploy canaries cover substantial isolation behavior but do not permanently require authenticated pinned CLI output/auth/tool-resolution contracts for every phase.

This PR is stage 1 of the user-confirmed four-PR delivery plan. It is security-sensitive and crosses the shared invocation model plus all provider callers, but it does not change process lifecycle, evaluation cost, Web/OpenAPI, Docker pinning, or deploy scripts. Existing history and old Issue #189 planning artifacts are not design inputs; current code and the current issue acceptance criteria are authoritative. **（ユーザー確認済み）**

## Goals / Non-Goals

**Goals:**

- Keep pre-filter activation code-gated until a separate Issue #154 change.
- Make each provider invocation's auth, tool policy, output schema, failure category, and model attribution explicit and testable.
- Provide the stable invocation contract required by later lifecycle, cost, and deploy-smoke stages.
- Complete PR 1 without enabling Issue #154 or changing live-trading behavior.

**Non-Goals:**

- Enabling `daemon.preFilterEnabled` in production or opening `PREFILTER_ACTIVATION_RELEASED`.
- Implementing or enabling real-money order execution.
- Replacing the existing PID 1 supervisor, signed deploy protocol, credential isolation, MCP runtime, or quarantine architecture.
- Adding a general billing integration for Claude/Codex subscriptions.
- Rescaling historical costs, synthesizing historical model attribution, or destructively backfilling lifecycle events.
- Treating infrastructure failure as strategy outcome or retroactively promoting an uncertain paper event to a fill.
- Process/cleanup terminal separation, orphan stress verification, session retention SLO, Codex price calculation, and deploy smoke/canary changes; these remain later stages.

## Decisions

### 1. Restore the release barrier in the same provider-contract PR

`LlmDaemonPreFilterGate` will receive the code-owned barrier result and require both the barrier and runtime configuration to be true. While the barrier is closed, eligible heartbeat triggers continue through the existing full-run path; the barrier does not suppress normal decision execution. Deployment preflight and regression tests will assert the same invariant.

The barrier fix ships with the provider invocation contract because it changes the same pre-filter rendering/composition surface and is too small to justify an independent merge/deploy cycle. An environment-only kill switch was rejected because runtime configuration is precisely the surface the code-owned barrier must dominate. **（ユーザー確認済み）**

### 2. Use one explicit invocation contract with phase-owned tool policy

`LlmInvocationRequest` will require a `ToolPolicy` that contains required and enabled tool identifiers. There will be no default. Empty policy renders strict empty MCP configuration and provider-supported no-tool arguments. Claude no-MCP rendering removes `--bare`, retains the copied auth source in the per-run home, and continues stripping parent LLM secrets. **（agent 仮決め）**

Model and effort remain request/config metadata. Pre-filter continues to request the pinned Haiku model explicitly. Reflection and report callers migrate in the same provider-contract PR so no consumer temporarily receives an implicit tool policy.

Keeping `--bare` with setup-token injection was rejected: it creates a second auth mode, expands secret handling, and does not improve the no-tool boundary over strict empty MCP configuration.

### 3. Treat provider output as a versioned adapter contract

Claude result JSON and Codex JSONL will be parsed by provider-specific adapters for the versions pinned in the Docker image. Adapters return a common typed result containing semantic text, usage, configured/observed model identity, provider failure detail, and schema version. Required terminal fields are validated; unknown extra fields are tolerated, while missing or incompatible terminal structure is a typed contract failure.

Failure categories are provider-neutral: `AUTHENTICATION`, `RATE_OR_SESSION_LIMIT`, `QUOTA_EXHAUSTED`, `OUTPUT_CONTRACT`, `PROCESS_TIMEOUT`, `PROCESS_EXIT`, `CLEANUP`, and `UNKNOWN_PROVIDER_FAILURE`. Provider adapters may attach a safe provider code, but raw Codex output, credentials, prompt, path, and tool payload remain unpersisted.

Codex model attribution will use output metadata when present and configured request metadata otherwise. Session-file scanning is removed. If neither is authoritative, usage remains and cost is unpriced. Continuing bounded session scans was rejected because a CLI storage layout is not an output contract. **（agent 仮決め）**

### 4. Keep this stage inside its bounded PR envelope

Line estimates use `git diff --numstat` additions plus deletions and include tests, documentation, generated OpenAPI JSON/types, and scripts. They exclude commit metadata and unchanged generated files.

| PR | Scope | Dependency | Expected changed lines | Hard stop |
|---|---|---|---:|---:|
| PR 1: provider invocation and activation contract | Reconnect the barrier; require `ToolPolicy`; remove Claude `--bare`; add strict no-tool auth, typed Claude/Codex adapters/failures, configured/output model attribution; remove Codex session scan; migrate callers. | current `main` | 700-1,100 across 10-18 files | 1,300 |

This PR is expected to change 700-1,100 lines across 10-18 files with a hard stop at 1,300 changed lines. Tests and current-state documentation count toward the limit. If the implementation needs process lifecycle, cost, deploy, DB schema, a new privilege, or more than 1,300 lines, it stops and returns the boundary for user decision. **（ユーザー確認済み）**

## Risks / Trade-offs

- [Real-provider smoke can be blocked by provider quota or outage] → Record a typed blocking result, retain the current production image, and require a later successful exact-candidate rerun; never substitute mocks for release evidence.
- [Provider CLI schema changes despite version pinning] → Fail the adapter contract, keep semantic execution fail-closed, and update the pinned version and adapter in one reviewed PR.
- [Barrier and docs drift again] → Assert barrier use in daemon composition and deploy preflight tests, and grep README/docs during each implementation completion check.
- [Removing `--bare` may not match a future Claude auth format] → Keep pre-filter code-gated, test copied-auth rendering and pinned output fixtures now, and require exact-candidate real-provider smoke in the later deploy stage. **（agent 仮決め）**
- [Provider error wording can drift] → Prefer structured fields/codes from pinned output and keep a bounded compatibility mapping; unknown output remains fail-closed and explicitly classified.

## Migration Plan

1. Merge PR 1 with `PREFILTER_ACTIVATION_RELEASED=false`; verify active runtime config cannot start pre-filter and all provider consumers use explicit tool policy.
2. Deploy through the existing workflow without changing root artifacts or compose; verify revision/readiness and that pre-filter remains inactive.
3. Archive this stage's OpenSpec change after merge, then create independent changes for process lifecycle, cost attribution, and deploy acceptance.

Rollback restores the previous image through the existing signed deploy protocol. No DB migration, root artifact, compose, or persisted credential format changes in this stage.

## Open Questions

No product decision is required before implementation. Exact CLI output samples and provider failure codes are implementation evidence collected from pinned fixtures and local CLI behavior; if they contradict the adapter assumptions, implementation stops and updates this design/spec rather than broadening heuristics silently. Real-provider auth success remains isolated by the closed pre-filter barrier and is proven in the later deploy-smoke stage.
