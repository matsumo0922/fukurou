## Why

Issue #189 requires timeout and cancellation to leave no orphan LLM process and requires semantic submission, process exit, and artifact cleanup to be distinguishable in audit evidence. Current `RUNNER_PHASE_COMPLETED` records one process-oriented `status` plus an optional `cleanupFailed`, so a decision committed before a timeout can still look like one undifferentiated phase failure.

## What Changes

- Make fallback process-tree termination attempt parent termination even when descendant enumeration fails.
- Make the launcher proxy convert TERM into a response-socket cancellation request, and make PID 1 terminate every provider/MCP job process group in the container before acknowledging cancellation.
- Add candidate-image timeout and cancellation stress evidence that proves no provider/MCP job remains after each run and cannot silently skip the production facilities.
- Add an additive terminal projection to runner-phase audit details that separately records semantic submission, process exit, and cleanup completion.
- Record a successful decision or falsification repository result as committed before the provider process exits, without inferring commitment from CLI output.
- Define the per-run home retention boundary as cleanup at invocation completion, quarantine on cleanup failure, and bounded destruction on container restart.
- Preserve the existing `status`, provider failure, usage, and risk-reduction behavior for compatibility.
- Keep Codex cost conversion and deploy-time real-provider smoke in later Issue #189 changes. **（ユーザー確認済み）**

## Capabilities

### New Capabilities

- `llm-process-cleanup-terminal`: Process-tree termination, semantic/process/cleanup terminal audit projection, and per-run artifact retention requirements for Issue #189 PR-2.

### Modified Capabilities

None.

## Impact

- Primary code: the fixed runtime supervisor/proxy, `ShellProcessRunner`, `LlmDecisionSubmissionGateway`, `LlmInvocationAuditor`, and their tests.
- Audit payload: additive fields under `RUNNER_PHASE_COMPLETED`; existing fields remain available.
- Documentation: MCP runtime, daemon/operations guidance, and design documentation describing current terminal and cleanup semantics.
- Runtime image: explicitly provision the process-group tools and run the supervisor cancellation selftest in the production-like canary.
- No DB migration, deploy bundle/root executor change, compose topology change, provider CLI contract change, Issue #154 activation, or live-trading enablement.
- The expected change is 800-1,200 changed lines with a 1,400-line hard stop. Crossing the hard stop or requiring a new persistent subsystem stops implementation for a scope decision. **（agent 仮決め）**
