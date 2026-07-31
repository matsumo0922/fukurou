## 1. Blocker registry が観測時刻を持つ

- [ ] 1.1 `LlmExecutionAdmissionHealth` の `recoveryBlockers` を `RecoveryBlockerRecord`（`registeredAt: Instant` と `registeredAtNanos: Long`）付き map にし、`registerRecoveryBlocker` へ両方を受け取らせる（再登録は最初の観測を保つ）
- [ ] 1.2 副作用のない bounded `snapshotRecoveryBlockers(after, limit)` を安定順で追加する
- [ ] 1.3 既存の登録元（`OneShotLlmRunner`、`LlmExecutionClaimSupervisor` の 2 箇所、`ReflectionTerminalPersistenceSupervisor`）へ観測時刻を配線する

## 2. Deadline-aware な判定 + audit の repository API

- [ ] 2.1 `CommandEventType.LLM_ADMISSION_BLOCKER_AUTO_RESOLVED` と `OpsRoutes` の projection 名を追加する
- [ ] 2.2 `LlmLaunchReservationRepository.resolveAdmissionBlockerIfTerminal(request, deadline)` を追加し、`Resolved` / `Retained(reason)` を返す
- [ ] 2.3 Exposed 実装で `prepareRecoveryStatement` による reservation 読み取りと `insertRecoveryEvent` による audit を 1 transaction にまとめる
- [ ] 2.4 in-memory 実装（`InMemoryLlmLaunchReservationRepository`）へ同じ意味論を実装する

## 3. Recovery scan への組み込み

- [ ] 3.1 `LlmExecutionRecoveryService.tick()` 冒頭に bounded batch + cursor の自動解除 step を実装する（monotonic quiet period 判定、`Resolved` のときだけ in-memory 解除）
- [ ] 3.2 解除 step の失敗と deadline 超過を tick の failure として伝播させる

## 4. 回帰テスト

- [ ] 4.1 unit テスト: UNKNOWN 終端 → blocker 登録 → DB 終端確認後の tick で `isHealthy()` が true に戻る
- [ ] 4.2 unit テスト: RUNNING / 静穏期間未経過 / lookup 失敗 / 記録なし で blocker が維持される
- [ ] 4.3 unit テスト: audit 失敗時に blocker が残り tick が failure を返す。解除済み blocker が再 audit されない
- [ ] 4.4 unit テスト: wall clock を後退させても monotonic 経過で解除される
- [ ] 4.5 unit テスト: batch limit を超える retained blocker があっても後続の解除可能 blocker が有限 tick 数で解除される
- [ ] 4.6 production call path テスト: `LlmExecutionRecoveryWorker` を application と同じ配線で起動し、blocker 登録 → 終端 → 自動解除 → readiness 復帰を確認する

## 5. ドキュメントと検証

- [ ] 5.1 `docs/mcp-runtime.md` の未確認終端後の復旧経路の記述を現在形で更新する
- [ ] 5.2 `openspec validate`、targeted test、`make test` / `make detekt` / `make build` を実行して記録する
