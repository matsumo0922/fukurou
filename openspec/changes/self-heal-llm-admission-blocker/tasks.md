## 1. Blocker registry が観測時刻を持つ

- [ ] 1.1 `LlmExecutionAdmissionHealth` の `recoveryBlockers` を登録時刻付き map にし、`registerRecoveryBlocker` へ `registeredAt` を受け取らせる（再登録は最初の観測時刻を保つ）
- [ ] 1.2 副作用のない `snapshotRecoveryBlockers()` を追加する
- [ ] 1.3 既存の登録元（`OneShotLlmRunner`、`LlmExecutionClaimSupervisor` の 2 箇所、`ReflectionTerminalPersistenceSupervisor`）へ観測時刻を配線する

## 2. Recovery scan が DB 終端事実で blocker を解除する

- [ ] 2.1 `CommandEventType.LLM_ADMISSION_BLOCKER_AUTO_RESOLVED` と `OpsRoutes` の projection 名を追加する
- [ ] 2.2 `LlmExecutionRecoveryService` に `CommandEventLog` を必須引数として足し、`LlmExecutionRecoveryWorker` から配線する
- [ ] 2.3 `tick()` 冒頭に自動解除 step を実装する（DB terminal かつ `hardTimeout + processTerminationGrace` 経過の AND、audit append 成功後に解除、lookup 失敗 / 記録なし / RUNNING は維持）

## 3. 回帰テスト

- [ ] 3.1 unit テスト: UNKNOWN 終端 → blocker 登録 → DB 終端確認後の tick で `isHealthy()` が true に戻る
- [ ] 3.2 unit テスト: RUNNING / 静穏期間未経過 / lookup 失敗 / 記録なし で blocker が維持される
- [ ] 3.3 unit テスト: audit append 失敗時に blocker が残り tick が failure を返す。解除済み blocker が再 audit されない
- [ ] 3.4 production call path テスト: `LlmExecutionRecoveryWorker` を application と同じ配線で起動し、blocker 登録 → 終端 → 自動解除 → readiness 復帰を確認する

## 4. ドキュメントと検証

- [ ] 4.1 `docs/mcp-runtime.md` の未確認終端後の復旧経路の記述を現在形で更新する
- [ ] 4.2 `openspec validate`、targeted test、`make test` / `make detekt` / `make build` を実行して記録する
