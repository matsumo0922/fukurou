## Context

The current runtime already starts provider processes in a Linux process group, owns production launch/reap through the fixed PID 1 supervisor, deletes per-run homes from tmpfs, and quarantines cleanup failures. `ShellProcessRunner` also has a non-Linux fallback that enumerates descendants before terminating the parent; an enumeration exception can leave the parent termination attempt unreachable. Audit currently writes one `RUNNER_PHASE_COMPLETED` event whose `status` describes the provider process and whose optional `cleanupFailed` flag does not say whether a decision or falsification had already committed.

This change is Issue #189 PR-2. PR #251 established the provider invocation contract. Cost evaluation and deploy-time real-provider smoke remain separate later changes. **（ユーザー確認済み）**

## Goals / Non-Goals

**Goals:**

- Ensure every started root process receives a bounded termination attempt even when fallback descendant discovery fails.
- Produce repeatable evidence that timeout and cancellation leave no process in the launched process group.
- Project semantic submission, process termination, and cleanup completion as three independent audit facts.
- Keep per-run session/auth copies bounded by the existing cleanup/quarantine/tmpfs lifecycle.
- Preserve risk-reducing committed decisions and existing audit consumers.

**Non-Goals:**

- Replacing the fixed PID 1 supervisor, launch protocol, Linux process-group model, or cleanup helper.
- Adding a durable lifecycle database, outbox, new event table, or historical backfill.
- Changing decision idempotency, provider CLI arguments, cost calculation, deploy hooks, compose topology, or Issue #154 activation.
- Claiming that a descendant tree exited when the runtime cannot prove it.

## Decisions

### 1. Keep Linux process groups as the production proof boundary

The existing `setsid` path remains the production path. Timeout, cancellation, and normal root exit terminate the whole process group and return `PROVEN_EXITED` only after both the root process and group are gone. The stress test runs 100 timeout cases and 100 cancellation cases through this real path and checks every recorded child PID. **（agent 仮決め）**

The fallback path is changed narrowly: descendant discovery is attempted, but root TERM/KILL is placed in a guaranteed final boundary. If descendant discovery fails, the root is still terminated and the original discovery failure is returned; the runtime does not upgrade the proof to `PROVEN_EXITED`. An injectable internal discovery hook supplies a deterministic regression test without changing the public runner contract. **（agent 仮決め）**

A global process sweep was rejected here because the current production supervisor already supplies the process-group owner and a container-wide sweep would make the JVM and unrelated operational processes part of this class's contract.

### 2. Add one additive terminal projection to the existing phase event

`RUNNER_PHASE_COMPLETED.details.terminal` contains three typed strings:

- `semanticCommit`: `COMMITTED`, `NOT_COMMITTED`, or `NOT_APPLICABLE`;
- `processExit`: `PROVEN_EXITED`, `UNCONFIRMED`, or `NOT_STARTED`;
- `cleanup`: `COMPLETED` or `FAILED`.

The existing `status`, `exitCode`, `cleanupFailed`, usage, provider failure, and redaction fields remain unchanged. One additive object avoids a DB migration and keeps existing bounded evaluation queries valid while allowing operators to distinguish the three terminal dimensions. **（agent 仮決め）**

`COMMITTED` is set only after `submitTerminalDecision` or `submitTerminalFalsification` returns successfully. The app-owned submission gateway stores that state in an atomic phase-local tracker. Merely seeing a provider tool call or response never implies commitment. A phase without a submission gateway is `NOT_APPLICABLE`; a gateway phase with no successful repository result is `NOT_COMMITTED`.

Process exit uses `ProcessRunResult.processTreeTerminationProof` when a result exists and the invocation-scoped termination registry for cancellation/failure paths. Started but uncertain is `UNCONFIRMED`; a process that never started is `NOT_STARTED`.

Cleanup is `FAILED` if process artifact, submission gateway, or unstarted manifest cleanup fails. Observation or event-append failures remain persistence failures rather than artifact cleanup facts.

### 3. Preserve semantic safety independently of process health

A committed `EXIT`, `REDUCE`, `ADJUST_PROTECTION`, or other accepted terminal submission remains recorded as committed if the provider later times out or cleanup fails. The phase invocation may still return a process/cleanup failure, but audit no longer implies the semantic write was absent. Failed or absent submission remains fail-closed for risk-increasing action exactly as before. **（ユーザー確認済み）**

### 4. Treat current tmpfs cleanup as the session retention limit

Normal, non-zero exit, timeout, cancellation, parse/start/request/render failure paths retain the existing non-cancellable cleanup. A cleanup failure retains the per-run artifact with the quarantine marker and rejects later LLM runs in the current container. Operator remediation or container restart destroys both marker and artifact because both are in `/run/fukurou/llm-homes` tmpfs. No retained provider session is used for model attribution after PR #251. **（agent 仮決め）**

## Risks / Trade-offs

- [A 200-case process stress test is slower or flaky on non-Linux hosts] → Run the proof only when the real `setsid` and process inspection facilities exist; keep deterministic unit coverage for fallback behavior.
- [Gateway commit state races with phase shutdown] → The provider waits for the gateway response, the tracker changes only after repository success, and the auditor snapshots it after invocation and gateway close.
- [Additive terminal fields drift from legacy `status`] → Derive both from the same invocation result, registry, and cleanup failure list; add compatibility assertions for existing fields.
- [Fallback termination cannot prove unknown descendants exited] → Retain `UNCONFIRMED` and the original failure while still guaranteeing the parent attempt.
- [Cleanup quarantine makes later runs unavailable] → Preserve the existing risk-reduction paths outside LLM invocation and document operator remediation; do not reinterpret infrastructure failure as strategy outcome.

## Migration Plan

1. Deploy as an additive application image with no schema or host mutation.
2. Confirm existing audit consumers tolerate the new nested `terminal` object and old fields remain present.
3. Verify production-like process and artifact canaries plus the 200-case process stress proof before merge.

Rollback restores the previous image. Existing event rows remain readable because no stored field is removed or reinterpreted.

## Open Questions

None. If implementation requires a new persistent lifecycle subsystem or exceeds 900 changed lines, stop rather than expanding this PR. **（agent 仮決め）**
