## Context

PR #270 introduced a default-off, one-shot production WebSocket disconnect seam solely to collect Issue #192 evidence. The route was never enabled and current production runs with `FUKUROU_ISSUE_192_WS_FAULT_ENABLED=false`. The later safety-fix branch is not merged. The cleanup does not depend on any production audit-row absence claim because decoding vocabulary remains.

The original two-arm verification would require waiting for two market states, serial owner approvals, temporary NAS configuration, multiple deploy/restart operations, and delayed unrelated deploys. （ユーザー確認済み）The owner values follow-on implementation throughput more than this additional one-off production evidence and has stopped the experiment.

## Goals / Non-Goals

**Goals:**

- remove the callable temporary runtime/configuration/test/documentation inventory introduced for Issue #192 while retaining only historical decoding vocabulary;
- preserve normal market-data and paper-fidelity behavior;
- leave no dormant activation surface for a cancelled experiment;
- deliver one reviewable cleanup PR without production mutation.

**Non-Goals:**

- execute either Issue #192 production fault;
- claim the original Issue #192 DoD passed;
- change schema, ledger, order lifecycle, runtime config, or normal gap/exclusion semantics;
- add replacement monitoring, a chaos framework, a generalized fault API, or new authorization;
- merge, deploy, edit NAS `.env`, or close the Issue in this autopilot run.

## Decisions

### 1. Remove rather than harden the default-off seam

（ユーザー確認済み）Do not merge the unmerged pre-verification safety fix. Remove the PR #270 surface from current `main` instead. A dormant but cancelled production mutation path has maintenance cost and no remaining product value.

### 2. Use one finite inverse inventory

（agent 仮決め）Use PR #270's changed-file inventory as the upper bound, then remove only symbols owned by the Issue #192 seam. Keep archived OpenSpec records as historical evidence. Add no subsystem and no follow-up stage.

The finite inventory is:

- application controller/route/holder/factory/module flag/background-worker callback;
- injected WebSocket disconnect interface, active-session mutation and terminal exception;
- command-event by-ID reader, while retaining Issue #192 event types as decoding-only vocabulary;
- production compose flag and current deploy documentation;
- injection-specific application/trading tests;
- no removal of activity catalog mappings, golden fixture, or locale strings because they are the retained decoding path.

For the shared GMO WebSocket file, reverse only PR #270 hunks. Keep the pre-existing `afterTerminalClaim` parameter and its normal terminal behavior; remove only the active-session reference, injection claim/abort path, `onClosed` bookkeeping added by PR #270, and the lambda argument that PR #270 supplied. Existing test files are edited hunk-by-hunk rather than deleted wholesale.

### 3. Retain audit decoding without production inspection or migration

（agent 仮決め・反証反映）Keep the requested/executed enum values, activity catalog mappings, golden entries, and locale strings. This costs only a small decoding vocabulary and avoids depending on a production-row absence assumption. Remove their writer and fixed-PK lookup path. Do not query production, delete rows, or add a migration. The conditional retention instruction in `docs/deploy.md` becomes unnecessary current-state procedure after the callable seam is removed, but the retained decoding behavior remains true in code.

### 4. Prove absence with existing behavior plus a narrow route regression

（agent 仮決め）Keep one small application-level assertion that the retired POST path returns `404`, then rely on the existing full validation and a bounded symbol grep for the removal inventory. Do not replace hundreds of deleted injection tests with new negative-test scaffolding.

## Risks / Trade-offs

- [shared production code is removed with the seam] → anchor deletion to the finite PR #270 symbol inventory and run the full repository validation.
- [historical audit row exists unexpectedly] → retained enum/catalog vocabulary continues to decode it; writer removal and no data mutation keep cleanup safe.
- [archived design text matches symbol grep] → explicitly allow the archived experiment record and current cleanup change; runtime/config/current-doc paths must be clean.
- [Issue is mistaken for completed] → PR and final Issue comment state that production injections were intentionally not performed and close as not planned only after cleanup deployment.

## Migration Plan

1. Merge the cleanup PR after review; this run does not merge.
2. Deploy through the existing signed deploy workflow. No schema or data migration is required.
3. Verify `/revision`, `/health/ready`, normal market-data connectivity, and `404` for the removed route.
4. Confirm NAS no longer needs an Issue #192 flag entry. Current production is already observed false; do not expose `.env` contents.
5. Close Issue #192 as not planned with a secret-free explanation that the experiment was cancelled for cost/value reasons.

Rollback is a normal application-image rollback only if cleanup causes a regression. Re-enabling the production fault experiment is not a rollback goal and requires a new issue/change.

## Open Questions

なし。価値判断はユーザーが production 注入中止と cleanup 1 PR に確定した。
