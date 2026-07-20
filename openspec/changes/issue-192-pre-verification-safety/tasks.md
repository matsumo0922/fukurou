## 1. Contract and falsification

- [x] 1.1 Read Issue #192, PR-1 archive, production deploy contract, and current main/production revision.
- [x] 1.2 Validate the PR-2 safety-fix proposal, design, delta spec, and PR-3 HANDOFF boundary.
- [x] 1.3 Run clean-context five-vector falsification and reconcile PENDING_CANCEL, target-identity, authority-split, and deploy-target blocking findings.
- [x] 1.4 Run a final fresh clean-context falsification on the split PR-2 scope and require blocking zero before delivery.

## 2. Safety implementation

- [x] 2.1 Enforce zero `PENDING_CANCEL` resting BUY entries before requested audit.
- [x] 2.2 Bind request/audit/final preflight to target UUID, exact expiry, and bounded minimum remaining TTL.
- [x] 2.3 Prove pending-cancel, identity replacement, target-state, expiry, and TTL rejection produce no audit and no disconnect.
- [x] 2.4 Run targeted `Issue192WsFaultSeamTest`, `:fukurou:detekt`, and `git diff --check`.

## 3. PR-2 delivery

- [x] 3.1 Run the initial full validation under the shared lease at the complete implementation HEAD and record command/result/SHA.
- [x] 3.2 Create a draft PR with `Refs #192`, Scenario evidence, falsification disposition, and `ドキュメント影響` line.
- [x] 3.3 Run the approved fresh reviewer, adjudicate anchored findings, and use fresh `gpt-5.6-sol` medium only for accepted fix clusters.
- [ ] 3.4 Run final full validation once at converged HEAD, synchronize PR/CI evidence, and post `APPROVED` or `HANDOFF` without merging.

## 4. Human HANDOFF and PR-3

- [ ] 4.1 Owner merges PR-2 and approves the signed `ROLL_FORWARD_ONLY` deploy after current inventory review.
- [ ] 4.2 Confirm production `/revision` contains PR-2 before any arm, then use `pr3-production-handoff.md` for baseline activation, owner NAS flag setup, rehearsal, both arm-specific owner gates, evidence, and cleanup.
- [ ] 4.3 PR-3 removes the entire temporary seam after terminal evidence or the 72-hour limit and performs post-merge route/NAS cleanup verification.
