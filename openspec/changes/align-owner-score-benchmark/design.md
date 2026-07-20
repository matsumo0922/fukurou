## Context

Issue #197 defines the owner score as rolling three-month post-cost bot return minus post-cost buy-and-hold return. The current `EvaluationMath.benchmark` starts from realized closed-trade PnL, while `EvaluationRoutes` states that buy and hold ignores fee and bot equity ignores unrealized PnL. Immutable evaluation reports copy that legacy calculation.

The repository already records most durable inputs: `equity_snapshots.account_epoch_id`, cash, BTC quantity, capture time, transactional `EPOCH_START` and `FILL` rows, `paper_account_epochs.runtime_config_hash`, market-data sessions/gaps, infrastructure gaps, and evaluation exclusions. Independent falsification found six blocking gaps in the first design: restart gaps begin at restart rather than outage start; account snapshots alone do not protect CURRENT cohort integrity; fallback fee was mislabeled as actual fee provenance and can disappear with config retention; new report fields are not rollback-readable by the old strict decoder; replacing `/evaluation/benchmark` is API-breaking; and range/scope compatibility was unspecified. This revision addresses them additively.

## Goals / Non-Goals

**Goals:**

- Compare bot, buy and hold, and cash over one 90-day JST window and common starting capital.
- Include bot unrealized PnL and a clearly labeled synthetic liquidation fee without mutating the ledger.
- Keep the synthetic fee assumption stable after runtime-config retention.
- Make crash downtime, truth coverage, population integrity, cutoff, and semantics version visible.
- Preserve existing benchmark API and immutable report compatibility.
- Bound all database reads and fail closed on missing or ambiguous evidence.

**Non-Goals:**

- Trading execution, SafetyFloor, account switching, ledger mutation, or retrospective fill/snapshot creation.
- Slippage, maker-rebate optimization, tax, funding, a new dashboard, provider/prompt ablation, or shadow counterfactuals.
- Rewriting or extending persisted immutable evaluation report JSON in this change.
- Proving historical periods where the daemon was intentionally disabled or halted but no durable interval can be reconstructed; this remains an explicit residual risk rather than an inferred valid period.

## Decisions

### 1. Add `/evaluation/owner-score`; preserve legacy contracts

（反証反映済み agent 決定）Add `GET /evaluation/owner-score` rather than replacing `/evaluation/benchmark`. The old endpoint keeps `from`, `to`, `epochId`, `cohort`, response fields, state values, and realized-equity semantics. Existing immutable report JSON and canonical hashes do not change; the Evaluation UI labels their chart “legacy realized equity”. The new panel alone is authoritative for `OWNER_SCORE_V1`.

This closes API and rollback hazards: old binaries never decode new report fields, and existing consumers receive no nullable point or state-set break. A future OpenSpec may migrate immutable reports after a forward-compatible reader exists.

### 2. Fix one completed-day window from one cutoff

（ユーザー確認済み）Buy and hold starts at each rolling window. （agent 仮決め）The route captures one optional `cutoff` instant. Omitted means `clock.instant()` once and `ROLLING`; supplied means `FIXED_CUTOFF`. The final observation date is the JST date immediately before the cutoff's JST date, and the first is 89 days earlier, excluding an incomplete current daily candle. Future cutoff is rejected; a pre-epoch or young epoch remains visible as `OUTSIDE_ACCOUNT_EPOCH`, not silently shortened.

### 3. Store an immutable synthetic fee policy per epoch

（反証反映済み agent 決定）Create append-only `paper_account_epoch_benchmark_policies` keyed by `(account_epoch_id, benchmark_semantics_version)` with `synthetic_taker_fee_rate`, `source_runtime_config_hash`, and `created_at`. Add an immutable UPDATE/DELETE trigger. `OWNER_SCORE_V1` deliberately uses the epoch's `paper.fallbackTakerFeeRate` only as a synthetic entry/exit assumption; it does not claim that historical fills used that rate. Actual exchange/fallback fees are already embedded in cash and are not recalculated.

Future activation that creates an epoch because `paper.initialCashJpy` changes inserts the policy in the same transaction as the epoch reset and `EPOCH_START` snapshot using the exact activated config values. A fee-only config activation does not create an epoch or replace its policy; the synthetic assumption intentionally remains fixed until the account epoch changes and may differ from the current fallback fee. Bootstrap may insert the current epoch's policy only after recomputing retained config hashes and finding exactly one match. Failure leaves the policy absent, returns `FEE_POLICY_UNAVAILABLE`, and requires a new audited epoch; it never falls back to current config/network state or mutates epoch history. Runtime-config pruning cannot remove the policy.

The table is added to both restore inventory and the critical-table primary-key verification list because, after config retention, it is the only durable fee-assumption evidence. A policy insert failure aborts the whole runtime-config activation transaction before its epoch/account/baseline changes commit; activation never succeeds without its policy.

Alternatives rejected: latest exchange fee drifts fixed-cutoff history; resolving retained configs on every request eventually fails retention; adding a mutable fee column to immutable epochs requires violating the existing epoch trigger.

### 4. Freeze DB inputs in one repeatable-read snapshot

（反証反映済み agent 決定）Add `fetchOwnerScoreSnapshot(cutoff, 90)` to the PostgreSQL evaluation repository. One explicitly configured read-only repeatable-read transaction fixes `query_now`, active epoch, epoch policy, 90 account states, gap intervals, and population integrity. Activation racing the query is observed wholly before or after commit. Each component has fixed row limits and a transaction-local statement timeout; truncation or integrity failure returns a typed unavailable reason, never partial success. A concurrency regression test pauses the read after resolving the epoch, activates a new epoch in another transaction, and proves the first request remains wholly on its original snapshot; a repository test also asserts the requested isolation/read-only mode rather than relying on Exposed defaults.

For each day end, a bounded lateral query selects exact-epoch snapshots with reason `EPOCH_START`, `BOOTSTRAP`, `FILL`, or `DAILY`. It uses cash/BTC only and ignores stored mark/equity. Add `(account_epoch_id, captured_at DESC)` index. If the maximum `captured_at` has conflicting cash/BTC rows, the day is `ACCOUNT_STATE_AMBIGUOUS`; random UUID order is not used. Identical duplicates are harmless.

### 5. Reconstruct crash-aware unknown intervals

（反証反映済み agent 決定）Normal `market_data_gaps` use `[started_at, recovered_at ?: queryNow)`. For `PROCESS_RESTART`, the evaluation projection joins its session and sets effective start to `last_transport_activity_at`, falling back to `connected_at`; effective end is `recovered_at ?: queryNow`. Infrastructure gaps use the existing half-open projection precedent, `[OPEN, matching CLOSE ?: queryNow)`. `queryNow` is the transaction's frozen request instant and is also capped by the requested cutoff for historical evaluation, so no gap extends beyond the evaluated evidence boundary. This conservatively marks both unobserved crash/OOM/power-loss intervals and gaps still open at request time. Both sources are intersected with JST days and bounded.

No gap row is rewritten. If a restart row has missing session evidence or impossible ordering, the source becomes unavailable. Intentional daemon-disabled/HALT intervals without reconstructable durable boundaries are not guessed; the UI/docs disclose this residual limitation. Adding a general operational-policy interval ledger is stage-out work, not a hidden expansion here.

### 6. Protect CURRENT strategy-population integrity

（反証反映済み agent 決定）Account liquidation state remains the economic truth, but it cannot by itself prove strategy attribution. The same transaction therefore checks every position/execution whose exposure or account mutation intersects the window, including positions opened before the window and still open/closed inside it. It reuses the existing execution-lineage rules and evaluation exclusions. Any legacy/unsupported semantics, missing/cross-epoch lineage, attribution missing, or excluded order/position produces counts and `INCONCLUSIVE`; affected cash is shown only as account evidence, not current KPI.

Gap days without affected strategy entities may still use the user-selected 90% rule. Once an actual position/order outcome is causally untrustworthy, the stricter population-integrity gate wins over the day threshold.

### 7. Liquidation calculation and coverage gate

（ユーザー確認済み）At least 81 of 90 days must be valid. Both boundary days must also be valid. （agent 仮決め）On valid close `P` with policy rate `f`:

`bot = cash + btcQuantity * P * (1 - f)`

The first-day bot liquidation equity is common capital `S`:

`buyHoldBtc = S / (startClose * (1 + f))`

`buyHold = buyHoldBtc * P * (1 - f)`

`cash = S`

Returns are `end / start - 1`; `ownerScore = botReturn - buyAndHoldReturn`; exact scaled comparison yields `BOT`, `BUY_AND_HOLD`, or `TIE`. Historical execution fees stay embedded in snapshot cash, while `f` applies only to synthetic benchmark entry and hypothetical liquidation.

Each day is `AVAILABLE` or `UNKNOWN` with stable reasons. `unknownDays` unions gap, outside-epoch, candle, account, and source failures. `gapDays` counts unioned operational gaps once per day. `coverageRatio=validDays/90`. Non-positive price/capital, missing boundary, fewer than 81 valid days, invalid policy, or invalid population yields null returns/score and `INCONCLUSIVE`.

Residual trade-off: the B&H synthetic entry pays a window-start fee while bot holdings carried into the window do not rebuy. This measures each strategy's actual state at window start versus a fresh B&H alternative and mildly favors carried bot exposure by one entry fee. The response exposes the assumption; changing it requires a new semantics version. Allowing up to nine unknown days can also retain some outage bias; every unknown day remains visible.

### 8. Add a truthful Evaluation UI panel

（反証反映済み agent 決定）The Evaluation page queries `/evaluation/owner-score` independently of immutable report generation. It shows semantics version, cutoff mode/time, fee assumption, bot/B&H/cash liquidation returns, owner score/winner, coverage and population-integrity counts. Unknown points render as chart gaps and an accessible reason table. The existing report chart title becomes “Legacy bot realized vs benchmark equity”; it is not merged with or used as the owner score.

This fits one implementation PR: new bounded repository/calculator/route and one additive panel. Immutable report schema/persistence is explicitly staged out, keeping review scope under the repository's 1,000-line guideline where practical.

## Risks / Trade-offs

- [Crash gap starts only at restart in raw data] → Evaluation derives `PROCESS_RESTART` start from durable last transport activity/connection time.
- [Open gap has no durable end yet] → Use the request's frozen `queryNow/cutoff` as the exclusive end, matching existing infrastructure-gap projection semantics.
- [Synthetic fee is confused with actual fill fee] → Name fields `syntheticFeeAssumption*`; state explicitly that snapshot cash already contains actual fees.
- [Runtime-config retention deletes fee evidence] → Copy the assumption into an immutable epoch policy during epoch activation/provisioning.
- [Legacy or excluded trade changes account cash] → Population-integrity gate withholds the current KPI.
- [Same-millisecond snapshots have random UUID ordering] → Treat conflicting maximum-time rows as ambiguous, not ordered.
- [Epoch changes during reads] → One repeatable-read transaction freezes all DB inputs.
- [Current daily candle is incomplete] → End at the last completed JST date and reject duplicate/missing dates.
- [Young epoch cannot satisfy a 90-day boundary] → Return explicit `OUTSIDE_ACCOUNT_EPOCH`; do not shorten the denominator.
- [Current production epoch/lineage cannot yet produce an available result] → Keep fail-closed state and show young-epoch, lineage, and gap reasons separately so the owner can see when evidence can become conclusive.
- [Epoch policy is omitted from restore-critical verification] → Add it to inventory and critical PK checks in the same schema change.
- [A pre-schema backup is restored after the critical-table profile is upgraded] → Document that the first post-deploy backup establishes the new restore profile; older snapshots can fail the new table check and require the version-matched legacy profile.
- [Intentional HALT/disabled intervals lack complete durable history] → Document as residual risk and do not invent intervals; stage out a policy-interval ledger.
- [90% coverage tolerates outage-biased days] → Preserve all reasons/counts and the user-selected threshold in versioned semantics.

## Migration Plan

1. Add the append-only epoch benchmark policy table/trigger, backup inventory/critical-table entries, snapshot reason/model support for `EPOCH_START`, and snapshot lookup indexes. Provision the active epoch policy only on exact hash verification.
2. Update future epoch activation to atomically insert policy plus epoch/account/`EPOCH_START`; add rollback-safe schema verification tests.
3. Add the repeatable-read owner-score snapshot query, crash interval projection, lineage integrity, and deterministic calculator with focused PostgreSQL/math tests.
4. Add `/evaluation/owner-score` and route-local OpenAPI without modifying `/evaluation/benchmark` or report persistence.
5. Regenerate Web types, add the owner-score panel, and relabel the existing immutable-report benchmark as legacy.
6. Update README/docs with the backup-profile transition: deploy schema/profile, take and verify the first post-deploy backup, and use a version-matched legacy critical-table profile when validating older pre-schema snapshots. Run focused tests, full test/detekt/build, Web tests/build, and OpenSpec validation.

Rollback removes the new route/panel and stops writing new policy rows. The additive table/index and existing policy rows can remain unread; no old response/report decoder sees new fields, and no ledger/account history is rewritten.

## Open Questions

- なし。coverage threshold と buy-and-hold window origin はユーザー確認済み。既存 active epoch の policy bootstrap が production evidence から確定できない場合は fail-closed のまま新 epoch activationを人間に求める。
