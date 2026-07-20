## Context

PR-1 is merged but not deployed. Its automatic deploy stopped at the schema-sensitive manual-review gate. PR-2 falsification found two controller-level races: `PENDING_CANCEL` was not enforced, and aggregate counts allowed an owner-approved order A to be replaced by unapproved order B. A later falsification also found a delivery cycle: these corrections cannot protect production while they remain in an unmerged PR that was originally intended to contain post-arm cleanup.

This is a single-owner hobby system. The design adds only the smallest application-local checks needed to bind the one-shot mutation to the owner's exact approval. It does not add global trading locks, NAS/GitHub coordination, or a reusable chaos platform.

## Goals / Non-Goals

**Goals:**

- make `PENDING_CANCEL` a controller-enforced no-mutation condition;
- bind owner approval to target order UUID, expiry, and minimum remaining TTL;
- prove every new rejection happens before requested audit and disconnect;
- publish a reviewed main revision that PR-3 can deploy and verify;
- retain a secret-free HANDOFF for baseline activation, manual deploy, two arms, and cleanup.

**Non-Goals:**

- any production write, deploy, disconnect, restart, or arm verdict in PR-2;
- continuous-state isolation across operator, database, NAS, and GitHub;
- schema/runtime-config changes, new auth, dashboard, matrix, or repeated injection;
- temporary seam cleanup before production verification reaches a terminal outcome.

## Decisions

### 1. Bind the mutation to the exact owner-approved order

The request carries `targetOrderId`, `expectedOrderExpiresAt`, and `minimumRemainingTtlSeconds`. Input parsing requires a UUID, ISO-8601 instant, and TTL from 1 through 86,400 seconds. The same boundary is stored in requested/executed audit payloads.

The controller final preflight reads the current order snapshot and requires:

- at least one `OPEN`, position-unlinked BUY resting entry;
- zero `PENDING_CANCEL`, position-unlinked BUY resting entries;
- zero open positions;
- exactly one snapshot matching the requested target UUID;
- the target remains `OPEN`, BUY, and position-unlinked;
- persisted expiry exactly matches the approved expiry;
- expiry is at least `observedAt + minimumRemainingTtlSeconds`.

Any mismatch returns a typed conflict before fixed requested audit append and before WebSocket disconnect. Gap-impact predicates are not changed.

### 2. Keep authority local and classify the remaining race honestly

The controller final read owns application-local baseline, market session/gap, order/position, and fresh trading launch-reservation checks. The operator owns request-time checks for backup/restore timers, GitHub deploy state, runtime-config mutation, all active `llm_runs`, and the computed recovery bound.

No global trading lock or cross-system transaction is introduced. If an application or operator-owned condition changes after its final check and the change is discovered after requested audit, PR-3 records `INVALID`, performs no retry, and restores normal operation. Neither PR-2 nor PR-3 claims continuous-state isolation.

### 3. Fixed requested audit remains the global burn

A correctly identified fixed requested row consumes the arm even if newer target-binding fields are absent or a startup request returns `STREAM_UNAVAILABLE` before the stream interface is published. The row is the mutation authority; the HTTP response is not. Payload fields support evidence but do not weaken fail-closed purpose-only consumption.

### 4. Split delivery at the deploy authority boundary

PR-2 contains only the safety fix, tests, OpenSpec record, and PR-3 HANDOFF. After review, merge, and signed `ROLL_FORWARD_ONLY` production deploy, `/revision` must prove that production includes the PR-2 fix before arm 1.

PR-3 owns canonical baseline activation, owner-performed NAS flag setup, mutation-free rehearsal, both separately owner-gated arms, terminal evidence, temporary seam cleanup, and post-merge cleanup verification. If either arm is non-PASS or the 72-hour window expires, PR-3 still removes the temporary surface; PASS only controls Issue #192 completion.

### 5. Models and review boundary

Implementation workers are fresh `gpt-5.6-sol` at medium effort. Because Claude Opus 4.8 was rate-limited, the user explicitly selected fresh `gpt-5.5` at xhigh effort for independent falsification. Final PR review uses the reviewer model currently approved by the user for this run; no Claude session is resumed.

## Risks / Trade-offs

- [state changes after final read] → PR-3 verdict is `INVALID`; requested audit burns the arm and prevents retry.
- [operator supplies unrelated order] → UUID/expiry/TTL binding and controller snapshot reject it before mutation.
- [temporary seam lives through PR-2] → default-off activation remains; PR-3 has a finite cleanup inventory and 72-hour limit.
- [PR-2 is merged before production evidence exists] → intentional; deploying the safety fix is a prerequisite, not evidence of arm success.
- [extra application/NAS/GitHub coordination grows scope] → rejected for this hobby system; use bounded operator checks and truthful evidence.

## Delivery Plan

1. Validate this change and close independent falsification blocking findings.
2. Run targeted tests, detekt, then one full validation at final HEAD.
3. Open a draft PR-2 with `Refs #192`, docs impact, Scenario evidence, and explicit no-production-mutation scope.
4. Run fresh independent review and converge accepted findings.
5. HANDOFF merge/deploy authority to the owner; do not claim production revision or route observations before merge.
6. After PR-2 production revision is confirmed, start PR-3 from `pr3-production-handoff.md`.
