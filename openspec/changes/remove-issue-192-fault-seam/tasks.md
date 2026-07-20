## 1. Contract and inventory

- [x] 1.1 Validate the cleanup proposal, delta spec, design, and finite PR #270 removal inventory.
- [ ] 1.2 Run clean-context five-vector falsification and reconcile every blocking finding before implementation.

## 2. Runtime cleanup

- [ ] 2.1 Remove the Issue #192 application controller, route, module flag, holder/factory wiring, and background-worker callback.
- [ ] 2.2 Remove the injected WebSocket disconnect interface, session mutation/exception, command-event by-ID reader, and injection-only event types.
- [ ] 2.3 Remove the production compose flag, current deploy documentation, activity catalog mappings, golden fixture entries, and localization strings.

## 3. Test cleanup and proof

- [ ] 3.1 Remove injection-only tests and restore affected structural expectations while retaining normal WebSocket, persistence, and paper-fidelity coverage.
- [ ] 3.2 Add one application-level regression proving the retired POST route is `404` and run the bounded absence grep outside OpenSpec archives/current cleanup artifacts.
- [ ] 3.3 Run initial full validation under the shared lease and record command, scope, wait time, result, and HEAD.

## 4. Delivery

- [ ] 4.1 Check docs/README/KDoc for stale current-state references, run `git diff --check`, commit, push, and create a draft PR with docs impact and explicit not-planned Issue disposition.
- [ ] 4.2 Run clean-context Claude Opus review, adjudicate anchored findings, and converge accepted must-fix/should findings.
- [ ] 4.3 Run final full validation once at converged HEAD, synchronize PR/CI evidence, and post `APPROVED` or `HANDOFF` without merging.
