## Why

Issue #192 PR-1 (`#270`, merge `7d34fe33`) introduced a default-off, one-shot WebSocket disconnect seam. Independent falsification of the production sequence found that PR-1 only gated aggregate resting-order counts. An owner-approved order can enter `PENDING_CANCEL` or be replaced by another order between operator inspection and the HTTP request, allowing an unapproved entity to be included in gap impact and evaluation exclusion.

The corrected controller must be merged and deployed before either production arm. Therefore PR-2 is limited to the pre-verification safety fix and reviewable handoff. Production mutation, evidence capture, and temporary seam cleanup move to PR-3 after PR-2 is merged and deployed.

## What Changes

- Reject the disconnect before requested audit when any position-unlinked BUY entry is `PENDING_CANCEL`.
- Bind the request and fixed audit payload to the owner-approved target order UUID, exact expiry, and bounded minimum remaining TTL.
- Re-read the authoritative order snapshot immediately before requested audit and reject target absence/replacement, lifecycle mismatch, expiry mismatch, or insufficient TTL without audit or disconnect.
- Keep the existing fixed-PK one-shot, exact session, fail-closed audit lookup, default-off flag, and gap-impact behavior unchanged.
- Preserve the post-final-read residual race as explicit PR-3 evidence: no global trading lock or cross-system coordinator is added; an observed drift is `INVALID` and never retried.
- Deliver `pr3-production-handoff.md` containing the production baseline/deploy blockers, two-arm evidence template, owner gates, and cleanup inventory.

## Capabilities

### New Capabilities

- `paper-websocket-fidelity-verification`: hardens the temporary Issue #192 verification seam before production use and defines the PR-2 → production verification → PR-3 cleanup boundary.

### Modified Capabilities

- none

## Impact

- Three temporary seam implementation/test files under `fukurou/`.
- No schema, runtime-config catalog, generic chaos framework, authorization layer, production mutation, NAS write, deploy, disconnect, or restart in PR-2.
- PR-2 must reach reviewed `main` and production before the arm-1 owner gate can be presented.
- Historical audit decoding remains unchanged; PR-3 removes the entire temporary seam after terminal verification or the 72-hour limit.
