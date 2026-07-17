## Implementation evidence

Implementation SHA: `93cee76d3156c2239a6df2431d9b523da44c095c`

| OpenSpec Scenario | Proving test at implementation SHA |
|---|---|
| New receipt commits before dispatch | `GmoPublicWebSocketMarketEventStreamTest.listener は complete message 受信時点で event の時刻と連番を固定する` |
| Duplicate source event reuses receipt authority | `PostgresPersistenceIntegrationTest.paper_market_receipt_duplicate_is_noop_and_changed_payload_is_typed_conflict` |
| Receipt persistence is not trustworthy | `GmoPublicWebSocketMarketEventStreamTest.listener は receipt persistence failure で trade を queue に渡さず terminal failure を1件返す`、`listener は receipt integrity conflict で trade を publish しない` |
| Receipt commits before order creation authority | `PostgresPersistenceIntegrationTest.receipt_and_order_transactions_serialize_on_session_barrier_in_both_directions` |
| Order creation authority precedes receipt commit | `PostgresPersistenceIntegrationTest.receipt_and_order_transactions_serialize_on_session_barrier_in_both_directions` |
| Session changes during order creation | `PostgresPersistenceIntegrationTest.stale_session_boundary_rejects_resting_order_without_consuming_intent` |
| Buffered pre-boundary event is processed after order commit | `PostgresPersistenceIntegrationTest.durable_receipt_boundary_rejects_one_thousand_pre_boundary_events_with_bounded_open_rows` |
| Post-boundary receipt is processed | `PostgresPersistenceIntegrationTest.post_boundary_exact_receipt_fills_even_when_wall_clock_boundary_disagrees` |
| Wall clock disagrees with durable ordering | `PostgresPersistenceIntegrationTest.post_boundary_exact_receipt_fills_even_when_wall_clock_boundary_disagrees`、`durable_receipt_boundary_rejects_one_thousand_pre_boundary_events_with_bounded_open_rows` |
| Exact receipt evidence is contradictory | `PostgresPersistenceIntegrationTest.invalid_receipt_evidence_cancels_entries_without_safety_detail_and_keeps_cursor_progress` |
| Legacy order has no admission boundary | `PostgresPersistenceIntegrationTest.paper_market_receipt_schema_is_additive_for_fresh_upgrade_and_old_reader_rollback`、`invalid_receipt_evidence_cancels_entries_without_safety_detail_and_keeps_cursor_progress` |
| Event belongs to another session | `PostgresPersistenceIntegrationTest.new_session_event_does_not_rebind_or_fill_old_session_resting_buy` |
| Position protection shares an event with ineligible entries | `PostgresPersistenceIntegrationTest.missing_entry_receipt_does_not_block_realtime_position_protection` |
| Existing database upgrades | `PostgresPersistenceIntegrationTest.paper_market_receipt_schema_is_additive_for_fresh_upgrade_and_old_reader_rollback` |
| New reader observes a legacy row | `PostgresPersistenceIntegrationTest.invalid_receipt_evidence_cancels_entries_without_safety_detail_and_keeps_cursor_progress` |
| Operator rolls back to an old reader | `OpsRouteTest.opsRoutes_haltResumeAndReadRiskState`、`PostgresPersistenceIntegrationTest.hardHaltCleanupCancelsRestingOrderWithHardHaltReason` と `docs/mcp-runtime.md` の receipt-aware reader rollback contract |
| Commit-order barrier repeats one thousand times | `PostgresPersistenceIntegrationTest.durable_receipt_boundary_rejects_one_thousand_pre_boundary_events_with_bounded_open_rows` |

Production root inventory is fixed by `DurableReceiptEligibilityWiringTest`: `TradingRuntimeFactory.connectedPostgres` owns the Exposed realtime order-boundary path, and `ProtectionReconcilerWorker` owns both the durable receipt repository and the Exposed event consumer.

## Validation ledger

Validated HEAD: `0e0ea11c53a87c99ee4086119027d15bde44c187`

All Gradle validation used the machine-wide validation lease, an isolated
temporary `GRADLE_USER_HOME`, and `GRADLE_OPTS=-Dorg.gradle.daemon=false`.

| Command and scope | Result | Lease wait | Wall time |
|---|---|---|---|
| Targeted `:trading:test` and `:fukurou:test` contract runs for receipt persistence/propagation, two-way commit barriers, 1,000 pre-boundary events, exact post-boundary fill, invalid evidence, old-session isolation, stale-session intent rollback, position protection, cursor/fill invariants, production wiring, and compile checks | PASS | 0s | 42s combined contract run; 1m 6s isolated 1,000-iteration run |
| `openspec validate issue-187-c-receipt-eligibility` | PASS | Not required | Less than 1s |
| `make test` | PASS | 0s | 8m 23s |
| `make detekt` | PASS | 0s | 19s |
| `make build` | PASS | 0s | 8s |

The final evidence/task commit changes only OpenSpec records after this tested
HEAD. `git diff --check`, the OpenSpec validator, and the stale dark-write grep
are rerun against the final tree.
