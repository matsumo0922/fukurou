## Context

The current runtime starts a launcher proxy through `ShellProcessRunner`, but the fixed PID 1 supervisor forks the real provider and MCP as separate children. The proxy process group therefore cannot prove that the real jobs exited. PID 1 owns and reaps those jobs but currently waits for natural child exit even after a launcher proxy disconnects. The runtime also deletes per-run homes from tmpfs and quarantines cleanup failures. Audit currently writes one LLM `RUNNER_PHASE_COMPLETED` event whose `status` describes the proxy-visible result and whose optional `cleanupFailed` flag does not say whether a decision or falsification had already committed.

This change is Issue #189 PR-2. PR #251 established the provider invocation contract. Cost evaluation and deploy-time real-provider smoke remain separate later changes. **（ユーザー確認済み）**

## Goals / Non-Goals

**Goals:**

- Ensure every started proxy receives a bounded termination attempt even when fallback descendant discovery fails.
- Make PID 1 terminate and reap all provider/MCP job process groups after any launcher proxy is abandoned.
- Produce candidate-image evidence that timeout and cancellation leave no supervised AI job.
- Project semantic submission, process termination, and cleanup completion as three independent audit facts.
- Keep per-run session/auth copies bounded by the existing cleanup/quarantine/tmpfs lifecycle.
- Preserve risk-reducing committed decisions and existing audit consumers.

**Non-Goals:**

- Replacing the fixed PID 1 supervisor, signed deploy protocol, launcher request schema, or cleanup helper.
- Adding a durable lifecycle database, outbox, new event table, or historical backfill.
- Changing decision idempotency, provider CLI arguments, cost calculation, deploy hooks, compose topology, or Issue #154 activation.
- Claiming that a descendant tree exited when the runtime cannot prove it.

## Decisions

### 1. Put provider/MCP cancellation at the existing PID 1 job owner

`ShellProcessRunner` continues to send TERM to its launcher proxy. The proxy signal handler only sets a cancellation flag; after `recv` returns `EINTR`, normal proxy code half-closes the request direction and keeps the response direction open. The supervisor polls stored response sockets and treats that half-close or an unexpected peer close as cancellation. It then performs one container-local AI cleanup: TERM every active provider/MCP job group, wait to a shared deadline, KILL remaining groups, reap every child, clear the fixed job table, and only then send a fixed cancellation acknowledgement to surviving response sockets. The application JVM, PostgreSQL, deploy executor, and risk-reducing paths are outside that table. This deliberately uses the user's personal-NAS assumption instead of introducing per-invocation dependency tracking. **（ユーザー確認済み）**

The launcher request schema and signed deploy protocol do not change. The cancellation trigger is existing socket lifecycle, and the cleanup owner is the existing supervisor job table. After `fork`, the parent establishes `setpgid(child, child)` and verifies the group before it opens the start gate; the child verifies the same group before dropping privileges and exec. The supervisor does not reply or clear a normally exited root job until its process group is empty. This closes both cancellation-before-group-ready and root-exits-before-background-child races. **（agent 仮決め）**

Provider code can call `setsid` or `setpgid`, so a job group alone is not a complete containment boundary. PID 1 also scans `/proc` for processes owned by the dedicated LLM UID 10002 or MCP UID 10003. Each job stores the authenticated launcher proxy identity from `SO_PEERCRED` as PID plus `/proc` start ticks. Only those exact live proxy identities whose response sockets remain stored are exempt from the cleanup inventory while they wait for acknowledgement; a reused PID, changed start ticks, the proxy's whole process group, and every other AI-UID process remain in scope. An in-scope AI-UID process outside an active job group is rogue and triggers global AI cleanup. Cleanup signals known job groups and in-scope AI-UID processes directly, repeatedly drains adopted children with `waitpid(-1, ..., WNOHANG)`, and repeats inventory to the shared deadline. It acknowledges success only when the job table and non-zombie AI-UID inventory excluding exact waiting proxies are empty and adopted zombies have been reaped. If either remains, launches stay disabled and no success acknowledgement is emitted. The application UID 10001 and root-owned supervisor are never targets. **（ユーザー確認済み）**

The proxy maps the post-cleanup supervisor acknowledgement to a reserved exit status. After sending acknowledgement, PID 1 closes the stored response sockets and removes the proxy exemptions. `ShellProcessRunner` marks supervisor-managed timeout/cancel as `PROVEN_EXITED` only when that exact acknowledgement is observed after TERM and its launcher proxy process group subsequently exits. The composed proof is therefore: PID 1 emptied provider/MCP jobs and non-exempt AI UID processes before acknowledgement, then the Kotlin owner observed its acknowledged proxy group exit. If the proxy is force-killed, the socket fails, or the acknowledgement is absent, the proof remains `UNCONFIRMED`; no automatic recovery is claimed, and later LLM admission remains stopped until operator verification or container restart clears process-local health. Normal completion retains proven exit only after PID 1 has reaped the root, emptied its group, and found no rogue AI-UID process. **（agent 仮決め）**

The fallback path is changed narrowly: descendant discovery is attempted, but root TERM/KILL is placed in a guaranteed final boundary. If descendant discovery fails, the root is still terminated and the original discovery failure is returned; the runtime does not upgrade the proof to `PROVEN_EXITED`. An injectable internal discovery hook supplies a deterministic regression test without changing the public runner contract. **（agent 仮決め）**

The candidate image explicitly installs `util-linux`, verifies `/usr/bin/setsid`, and runs the supervisor's real job-table disconnect selftest for 100 timeout-shaped and 100 cancellation-shaped abandoned proxies. Fixtures cover cancellation on both sides of start-gate release, a root that exits zero while a TERM-ignoring child stays in its group, a child that creates a new session, exact waiting-proxy exemption, and stale/reused proxy PID rejection. Every case asserts internal jobs and non-exempt LLM/MCP UID processes are empty before acknowledgement, then asserts all proxies exit after acknowledgement. The production-like canary treats missing facilities or skipped cases as failure. **（agent 仮決め）**

### 2. Add one additive terminal projection to the existing phase event

`RUNNER_PHASE_COMPLETED.details.terminal` contains three typed strings:

- `semanticCommit`: `COMMITTED`, `NOT_COMMITTED`, `UNKNOWN`, or `NOT_APPLICABLE`;
- `processExit`: `PROVEN_EXITED`, `UNCONFIRMED`, or `NOT_STARTED`;
- `cleanup`: `COMPLETED` or `FAILED`.

The existing `status`, `exitCode`, `cleanupFailed`, usage, provider failure, and redaction fields remain unchanged. One additive object avoids a DB migration and keeps existing bounded evaluation queries valid while allowing operators to distinguish the three terminal dimensions. **（agent 仮決め）**

`COMMITTED` is set only after `submitTerminalDecision` or `submitTerminalFalsification` returns successfully. The app-owned submission gateway stores a phase-local state machine: `NOT_ATTEMPTED`, `IN_FLIGHT`, `COMMITTED`, or `REJECTED`. Merely seeing a provider tool call or response never implies commitment. A phase without a submission gateway is `NOT_APPLICABLE`; a completed gateway with no accepted request is `NOT_COMMITTED`. If shutdown times out or cancellation can race a blocking repository transaction, audit reports `UNKNOWN`, never a false `NOT_COMMITTED`. **（agent 仮決め）**

Process exit uses `ProcessRunResult.processTreeTerminationProof` when a result exists and the invocation-scoped termination registry for cancellation/failure paths. A supervisor-managed timeout/cancel does not promote proxy exit into provider-tree proof. Started but uncertain is `UNCONFIRMED`; a process that never started is `NOT_STARTED`.

Cleanup is `FAILED` if process artifact, submission gateway, or unstarted manifest cleanup fails. Observation or event-append failures remain persistence failures rather than artifact cleanup facts. The terminal projection is required only for LLM invocation events produced by `LlmInvocationAuditor`; deterministic phases produced by `OneShotLlmRunAuditRecorder` keep their current payload because semantic/process/cleanup terminals are not applicable to them. The two-producer inventory is fixed by a contract test. **（agent 仮決め）**

### 3. Preserve semantic safety independently of process health

A committed `EXIT`, `REDUCE`, `ADJUST_PROTECTION`, or other accepted terminal submission remains recorded as committed if the provider later times out or cleanup fails. The phase invocation may still return a process/cleanup failure, but audit no longer implies the semantic write was absent. Failed or absent submission remains fail-closed for risk-increasing action exactly as before. **（ユーザー確認済み）**

### 4. Treat current tmpfs cleanup as the session retention limit

Normal, non-zero exit, timeout, cancellation, parse/start/request/render failure paths retain the existing non-cancellable cleanup. A cleanup failure retains the per-run artifact with the quarantine marker and rejects later LLM runs in the current container. Operator remediation or container restart destroys both marker and artifact because both are in `/run/fukurou/llm-homes` tmpfs. No retained provider session is used for model attribution after PR #251. **（agent 仮決め）**

## Risks / Trade-offs

- [A 200-case process stress test could disappear on a developer host] → Merge evidence comes from the candidate runtime image; missing `setsid`, `/proc`, or any skipped case fails the canary. Host unit tests remain supplemental.
- [Gateway commit state races with phase shutdown] → Report `UNKNOWN` whenever the worker has not reached a terminal state; never infer non-commit from executor interruption.
- [Additive terminal fields drift from legacy `status`] → Derive both from the same invocation result, registry, and cleanup failure list; add compatibility assertions for existing fields.
- [Supervisor cannot prove the AI inventory is empty] → Emit no cancellation acknowledgement, keep launches disabled, and require operator verification or container restart; never convert uncertainty into proof.
- [Cleanup quarantine makes later runs unavailable] → Preserve the existing risk-reduction paths outside LLM invocation and document operator remediation; do not reinterpret infrastructure failure as strategy outcome.

## Migration Plan

1. Deploy as an additive application image with no schema or host mutation.
2. Confirm existing audit consumers tolerate the new nested `terminal` object and old fields remain present.
3. Verify the candidate runtime image selects the production launcher/supervisor topology and completes all 200 abandoned-proxy cases without skip before merge.

Rollback restores the previous image. Existing event rows remain readable because no stored field is removed or reinterpreted.

## Open Questions

None. If implementation requires a new persistent lifecycle subsystem or exceeds 1,400 changed lines, stop rather than expanding this PR. **（agent 仮決め）**
