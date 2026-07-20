## ADDED Requirements

### Requirement: Fidelity verification uses future-only production evidence
The verification procedure MUST evaluate the deployed `PAPER_WS_V1` path from facts created at or after an explicit preflight boundary. It MUST bind every result to the production revision, immutable image digest, account epoch, runtime config identity, container generation, market-data session, target entity IDs, and observation timestamps. It MUST NOT create or infer fills from historical prices, rewrite existing ledger or exclusion rows, or treat current state as proof of an unobserved past state.

#### Scenario: An arm starts from a valid production boundary
- **WHEN** revision, image, epoch, runtime config, container, session, entity, and observation identities are all readable before mutation
- **THEN** the procedure records them as the immutable comparison boundary for that arm

#### Scenario: A required production fact is unavailable
- **WHEN** any required identity, query, or timestamp cannot be read or has conflicting authorities
- **THEN** the arm remains `UNKNOWN`, performs no injection, and does not substitute a code default or current display value

### Requirement: Fault injection is admitted only in the requested paper states
The procedure MUST run the two arms serially and MUST complete recovery and verdict for the first arm before admitting the second. The WebSocket arm MUST require at least one open resting BUY entry and no open position. The process-restart arm MUST require at least one open position and no open or pending-cancel resting BUY entry. Both arms MUST require `PAPER` mode, a connected market-data session, zero unresolved market-data gaps, matching active-epoch/account/runtime-config cash baselines, and no concurrent deploy, runtime-config mutation, backup/restore maintenance, active LLM run, or active launch reservation at the final preflight read. The next scheduled backup or restore timer MUST also fall outside the arm's maximum observation window. Immediately before each mutation, the operator MUST present the exact preflight inventory, expected order mutation, irreversible evaluation exclusions, and recovery bound, and MUST obtain an explicit owner go/no-go for that arm. Design or falsification approval MUST NOT be treated as production-mutation approval.

#### Scenario: Requested state has not appeared
- **WHEN** the normal soak has no resting entry for the WebSocket arm or no open position for the restart arm
- **THEN** the procedure continues ordinary soak without creating an order, position, fill, or historical reconstruction solely to satisfy the test

#### Scenario: State changes between preflight and injection
- **WHEN** the target entity fills, cancels, closes, changes epoch, or otherwise leaves its admitted state before the mutation begins
- **THEN** the procedure performs no injection and returns to the normal waiting state

#### Scenario: Concurrent maintenance or execution is present
- **WHEN** a deploy, config mutation, backup/restore operation, active LLM run, launch reservation, or earlier unresolved gap overlaps final preflight
- **THEN** the procedure defers the arm rather than combining unrelated state transitions with its evidence window

#### Scenario: Arm-specific owner approval is absent
- **WHEN** the exact affected inventory and irreversible evaluation impact have not received an explicit owner go decision for the pending arm
- **THEN** the procedure performs no mutation even if every technical precondition passes

### Requirement: WebSocket transport is disconnected once without stopping the process or database path
The WebSocket arm MUST use a temporary application-owned fault controller to disconnect exactly the active WebSocket session whose ID equals the final preflight session, while preserving REST clients, the application process, all container network attachments, public origin, and PostgreSQL path. The session-local one-shot operation MUST first obtain the right to mutate by compare-and-set, call `WebSocket.abort()`, and after a successful abort deliver one typed injected-disconnect failure through the same listener terminal-claim boundary used by production `onError` and `onClose`. It MUST produce one immediate `DISCONNECTED` signal downstream; it MUST NOT claim to verify GMO-peer or kernel callback dispatch. The controller MUST NOT acquire the global trading lock and MUST serialize only its own requests with a controller-local mutex.

The controller and `POST /ops/issue-192/ws-disconnect` route MUST exist only when the temporary `FUKUROU_ISSUE_192_WS_FAULT_ENABLED` deployment flag is explicitly true; its default MUST be false. The route MUST be hidden from generated OpenAPI. The request MUST include an injection ID, expected session ID, fixed Issue #192 purpose, and owner-approved reason. Existing Cloudflare Access MUST protect the external `/ops/*` entry. Because this is a temporary seam in a single-owner hobby system, it MUST NOT add a dedicated token, capability file, Dockerfile change, OpenAPI operation, or WebUI. Internal-process invocation is an accepted residual risk bounded by exact-session validation and the durable one-shot gate.

The durable one-shot gate MUST perform at most two primary-key lookups against `command_event_log`: requested event ID `588ce39f-90ec-4479-9430-f22a6d0356a9` and executed event ID `0367f844-595a-4ed7-8480-43a1d3e5df6c`. At startup and for every request, either row MUST globally consume the arm regardless of request injection ID and reject all further disconnects, including after the process-restart arm. A lookup timeout, DB unavailability, wrong event type, or conflicting payload MUST fail closed with no disconnect and MUST NOT fall back to an event-type table scan. All request/session/preflight rejections MUST be decided before the requested fixed audit is inserted. Both fixed events MUST use `toolName=issue192-ws`, MUST use their own bare event UUID as `clientRequestId`, and MUST store injection/session identity in payload so the existing command-event persistence policy accepts them. The controller MUST durably insert the secret-free requested fixed audit before disconnecting, and durably insert the executed fixed audit only after successful disconnect. A requested audit without an executed audit MUST remain `UNKNOWN`, permanently consume this arm, and MUST NOT be deleted or retried after request loss, CAS failure, abort failure, or process restart. The controller MUST never infer WebSocket identity from host PID, endpoint, socket duration, byte count, or four-tuple.

The temporary production compose MUST pass `${FUKUROU_ISSUE_192_WS_FAULT_ENABLED:-false}`. The operator MUST set the single NAS `.env` entry before the verification deploy. After evidence, a reviewed revision MUST remove the controller, route, and compose flag while historical audit vocabulary remains readable; only after that revision proves the route absent may the operator remove the `.env` entry.

#### Scenario: Resting entry exists during a transport disconnect
- **WHEN** the temporary route is enabled and a valid owner-approved request names the current active WebSocket session and an unused injection ID
- **THEN** the original process/container generation remains alive, the live socket is aborted, the production listener terminal boundary emits one immediate failure, the preflight session receives one `DISCONNECTED` gap with non-null impact time, and every affected resting BUY entry is canceled with `MARKET_DATA_GAP`

#### Scenario: Transport recovers
- **WHEN** the application reconnects after the exact socket is destroyed
- **THEN** the same process/container start identity and network attachments remain, exactly one gap exists in the arm window, and a new connected session, at least one durable receipt from that session, a non-null target-gap recovery time, restored readiness, and normal public connectivity are observed before any verdict can finish

#### Scenario: Gap impact is not observed before the bound
- **WHEN** active liveness/reconnect values plus the specified grace expire or the hard five-minute cap is reached
- **THEN** the procedure marks the arm `INVALID` or `UNKNOWN`, does not extend the outage or automatically repeat the injection, and uses the existing reviewed service-recovery path until normal operation is proven

#### Scenario: Injection identity is stale or ambiguous
- **WHEN** the expected session is no longer active, either fixed requested/executed audit row exists, or the final preflight no longer admits the arm
- **THEN** no session is aborted, the route returns a typed conflict or is absent, and a new owner decision is required before any new injection identity is created

#### Scenario: Audit cannot precede mutation
- **WHEN** either fixed-ID lookup is incomplete or the controller cannot durably append the secret-free requested audit before abort
- **THEN** no session is aborted, the gate fails closed, and the arm remains `UNKNOWN`

#### Scenario: Requested audit exists without execution
- **WHEN** the fixed requested row exists but the fixed executed row does not after a CAS, abort, response, or process failure
- **THEN** the arm is permanently `UNKNOWN`, every later injection request is rejected, and neither audit deletion nor human-approved reinjection is allowed

#### Scenario: A non-target gap reason appears
- **WHEN** the target session produces `TRANSPORT_LIVENESS_LOST`, `DATABASE_FAILURE`, `INVALID_MESSAGE`, `SEQUENCE_GAP`, or any reason other than the injected `DISCONNECTED`, or another gap appears in the arm window
- **THEN** the procedure records every gap, returns `INVALID` or `UNKNOWN`, completes service restoration, and neither normalizes the reason nor reinjects

#### Scenario: A receipt-authorized trade was buffered before terminal failure
- **WHEN** a future-only durable trade receipt admitted before the listener terminal claim is delivered before the queued disconnect failure and changes the target order
- **THEN** the procedure preserves its full lineage, classifies a causal pre-gap fill as known `INVALID`, and does not treat it as retroactive or automatically reinject

#### Scenario: Injected exception classification is stable
- **WHEN** the combined operation creates its typed exception
- **THEN** its fixed message is `issue-192 injected websocket disconnect`, contains no `sequence gap` discriminator, and maps exactly to `DISCONNECTED`

#### Scenario: Reconnection or service restoration fails
- **WHEN** a connected session, post-recovery durable receipt, zero unresolved gaps, readiness, or public connectivity cannot be proven after mutation
- **THEN** service recovery through the existing reviewed path takes precedence over evidence collection, the arm cannot pass, and no second fault injection starts

#### Scenario: Temporary seam is removed after evidence
- **WHEN** both arm verdicts and evidence are complete
- **THEN** after deploy-specific owner approval with the added exclusion inventory, or without that approval when flat state is proven, a reviewed revision removes the controller, route, and compose flag, preserves historical audit decoding and all ledger/gap/exclusion evidence, proves the route is absent, and removes the NAS `.env` entry only after the deploy succeeds

#### Scenario: An unrelated deploy occurs during verification
- **WHEN** revision or image changes between the verification deploy and cleanup deploy outside this change
- **THEN** the current arm is `INVALID`, injection is not repeated or rearmed, service recovery and identity checks complete, and the plan returns to flat-state or deploy-specific owner approval before continuing

#### Scenario: Verification window reaches its bound
- **WHEN** 72 hours pass after the verification deploy before both arm preconditions and verdicts complete
- **THEN** the procedure performs the flat-state or owner-approved cleanup deploy first, removes the seam, never reinjects after a requested audit, and moves any unfinished verification to a new change

### Requirement: Open-position process restart is recovered from durable state
The process-restart arm MUST restart the Ktor container exactly once after its own explicit owner go decision, without replacing its image, restarting PostgreSQL, changing runtime config, or deploying another revision. Startup MUST convert the prior connected session into a `PROCESS_RESTART` gap, apply exclusions idempotently, and start a different market-data session.

#### Scenario: Open position exists during planned restart
- **WHEN** a valid process-restart arm restarts the Ktor container once
- **THEN** container and image identity remain unchanged, process start time advances, the prior session has a `PROCESS_RESTART` gap with non-null impact time, and every affected open position and related decision run has a durable exclusion

#### Scenario: Realtime management resumes after restart
- **WHEN** the restarted process becomes ready and receives a trade on the new session
- **THEN** the new session has a durable receipt, the restart gap has a non-null recovery time, and the position remains open or changes state only through an allowed execution joined to that new receipt

#### Scenario: Restart recovery does not converge
- **WHEN** readiness, stale-session impact, a new connected session, or a post-restart receipt is absent after the bounded recovery period
- **THEN** the procedure does not issue another restart to overwrite evidence, restores service through the existing reviewed recovery path, and the arm cannot pass

### Requirement: Every observed execution retains complete durable source lineage
For each arm, the procedure MUST enumerate every `PAPER_WS_V1` execution created within the evidence window. Every enumerated execution MUST have account epoch, runtime config hash, complete source fields, and exactly one matching durable receipt on source session and sequence. Receipt timestamp, socket-observed timestamp, side, price, and size MUST agree with the execution source evidence, and the source event MUST satisfy the target entity's causal eligibility boundary.

#### Scenario: An execution occurs in an evidence window
- **WHEN** any execution is created between the arm preflight and recovery evidence timestamps
- **THEN** all lineage and receipt joins pass and the evidence records the total, matched total, and violating total

#### Scenario: No execution occurs in an evidence window
- **WHEN** the arm reaches recovery without creating an execution
- **THEN** the evidence records execution total zero, does not claim that a fill path was observed, and still verifies post-recovery receipt continuity and zero violating executions

#### Scenario: A resting target receives a post-gap execution
- **WHEN** the WebSocket arm's target order has an execution whose causal source is at or after the gap boundary instead of being canceled
- **THEN** the result is `HARD_FAIL` and MUST NOT be normalized into a successful reconnect

#### Scenario: A recovered position closes on a new realtime event
- **WHEN** the restart arm's target position closes after recovery
- **THEN** its closing execution is accepted only when it joins exactly to the new session's durable receipt and not to REST, history, the stale session, or an unreceived event

### Requirement: Gap-affected entities remain outside current strategy KPIs
Each arm MUST derive the authoritative affected-entity inventory from the target gap's durable `evaluation_exclusions` and the order, position, and decision-run states immediately after `impact_applied_at`, then compare it with the preflight inventory. Every exclusion reason MUST exactly equal the target gap's `MarketDataGapReason.name`. Any additional gap or affected entity within the arm window MUST be recorded and MUST make the arm `INVALID`. The procedure MUST query the explicit active epoch and `CURRENT` cohort through the production evaluation surface and MUST verify that excluded positions are absent from the strategy-eligible closed-trade population. A still-open position MUST be reported as not yet eligible for a closed-trade observation rather than as positive proof of a future exclusion.

#### Scenario: Gap impact covers affected entities
- **WHEN** a gap impact transaction completes
- **THEN** every affected order, position, and related decision run has the target gap identity and exact `MarketDataGapReason.name`, with no silently missing entity

#### Scenario: Additional gap or affected entity appears
- **WHEN** the arm window contains a gap other than the single target gap or an affected entity absent from preflight
- **THEN** every delta is preserved in evidence, the arm is `INVALID`, and the next arm does not start

#### Scenario: An affected position closes within the evidence window
- **WHEN** a position bearing the arm's gap exclusion becomes closed
- **THEN** it is absent from the active-epoch `CURRENT` strategy-eligible trade set and is counted in exclusion evidence

#### Scenario: An affected position remains open
- **WHEN** an excluded target position has not closed by arm completion
- **THEN** the evidence records the durable exclusion and `closed KPI non-mixing not yet instantiated` rather than waiting for or fabricating a close

#### Scenario: Evaluation scope or query is uncertain
- **WHEN** the API resolves a different epoch/cohort, the DB query is bounded out, or either authority is unavailable
- **THEN** the KPI result is `UNKNOWN` and the arm cannot pass

### Requirement: Hard failures stop the verification without silent retry
The procedure MUST classify outcomes as `PASS`, `HARD_FAIL`, `INVALID`, or `UNKNOWN`. Retroactive fill, silent resolution of an ambiguous outcome, or inclusion of an excluded entity in current strategy KPIs MUST be `HARD_FAIL`. Operator/preflight races and incomplete evidence MUST NOT be relabeled as correctness failures, but `INVALID` and `UNKNOWN` MUST NOT pass. No arm MUST be automatically reinjected after a mutation has occurred. After any mutation, every verdict MUST prove a connected market-data session, a post-recovery durable receipt, zero unresolved gaps, readiness, and public connectivity; otherwise the arm remains in service recovery and MUST NOT finish.

#### Scenario: A hard failure is observed
- **WHEN** any retroactive execution, silently resolved ambiguous state, or current-KPI contamination is proven
- **THEN** normal soak completion, the next arm, and Issue closure stop, while all original ledger/gap/exclusion evidence remains unmodified for a separate corrective change

#### Scenario: Evidence is incomplete but no counterexample is proven
- **WHEN** an arm has a query failure, topology drift, preflight race after mutation, or unrecovered observation with no proven hard failure
- **THEN** it ends as `INVALID` or `UNKNOWN`, is not counted as hard-fail zero, and requires explicit human direction before any repeat

#### Scenario: Both requested arms pass
- **WHEN** both arms have complete identity, recovery, lineage, exclusion, and KPI evidence with hard-fail count zero and unknown count zero
- **THEN** the two fault-injection DoD items for Issue #192 are satisfied, while ordinary soak continues without a closed-trade or regime-count completion threshold
