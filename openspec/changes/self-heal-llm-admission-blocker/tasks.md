## 1. Blocker registry が観測時刻を持つ

- [ ] 1.1 `LlmExecutionAdmissionHealth` の `recoveryBlockers` を `RecoveryBlockerRecord`（`registeredAt: Instant`、`registeredAtNanos: Long`、安定な `resolutionAttemptId: UUID`）付き map にし、`registerRecoveryBlocker` へ観測時刻を受け取らせる（再登録は最初の record を保つ）
- [ ] 1.2 副作用のない bounded `snapshotRecoveryBlockers(after, limit)` を安定順で追加する
- [ ] 1.3 既存の登録元（`OneShotLlmRunner`、`LlmExecutionClaimSupervisor` の 2 箇所、`ReflectionTerminalPersistenceSupervisor`）へ観測時刻を配線する

## 2. Deadline-aware な判定 + audit の repository API

- [ ] 2.1 `CommandEventType.LLM_ADMISSION_BLOCKER_AUTO_RESOLVED` と `OpsRoutes` の projection 名を追加する
- [ ] 2.2 `LlmLaunchReservationRepository.resolveAdmissionBlockerIfTerminal(request, deadline)` を追加し、`Resolved` / `Retained(reason)` を返す
- [ ] 2.3 Exposed 実装で 1 transaction 内に「既存 audit の readback → reservation 読み取り → audit insert」を順に置き、すべて `prepareRecoveryStatement` を通す（readback は id / decision_run_id / tool_call_id / client_request_id / tool_name / event_type と payload の blocker identity まで exact match し、一致で再 insert せず `Resolved`、不一致は failure）
- [ ] 2.4 in-memory 実装（`InMemoryLlmLaunchReservationRepository`）へ同じ意味論を実装する

## 3. Recovery scan への組み込み

- [ ] 3.1 `LlmExecutionRecoveryService.tick()` 冒頭に bounded batch + cursor の自動解除 step を実装する（monotonic quiet period 判定、`Resolved` のときだけ in-memory 解除）
- [ ] 3.2 tick deadline から handoff reserve を引いた sub-deadline を作り、候補の repository call へはそれを渡す。件数上限と sub-deadline 枯渇の両方で正常打ち切りする（打ち切りは failure にしない）
- [ ] 3.3 cursor を候補ごとに前進させる（失敗した候補も含む）
- [ ] 3.4 failure を分類する: sub-deadline 由来の時間切れは正常 handoff（cursor 前進 + scan へ進む）、budget が残る状態での lookup / audit / identity 不一致は cursor 前進のうえ tick failure として伝播

## 4. 回帰テスト

- [ ] 4.1 unit テスト: UNKNOWN 終端 → blocker 登録 → DB 終端確認後の tick で `isHealthy()` が true に戻る
- [ ] 4.2 unit テスト: RUNNING / 静穏期間未経過 / lookup 失敗 / 記録なし で blocker が維持される
- [ ] 4.3 unit テスト: audit 失敗時に blocker が残り tick が failure を返す。解除済み blocker が再 audit されない
- [ ] 4.4 unit テスト: wall clock を後退させても monotonic 経過で解除される
- [ ] 4.5 unit テスト: batch limit を超える retained blocker があっても後続の解除可能 blocker が有限 tick 数で解除される
- [ ] 4.6 unit テスト: audit commit 済みで caller が failure を観測した後、次 tick が readback で `Resolved` に到達し audit が重複しない
- [ ] 4.7 unit テスト: 先頭候補が毎回失敗しても cursor が前進し、後続の解除可能 blocker が有限 tick 数で解除される
- [ ] 4.8 unit テスト: 候補評価が遅い場合、sub-deadline 到達で正常 handoff して同じ tick で stale-claim scan が start reserve 内に開始でき、tick が failure にならない
- [ ] 4.9 unit テスト: 同一 invocationId で claimant token 違いの blocker が 2 つあるとき、片方の audit を他方が自分のものと誤認せず failure になる
- [ ] 4.10 production call path テスト: `LlmExecutionRecoveryWorker` を application と同じ配線で起動し、blocker 登録 → 終端 → 自動解除 → readiness 復帰を確認する

## 5. ドキュメントと検証

- [ ] 5.1 `docs/mcp-runtime.md` の未確認終端後の復旧経路の記述を現在形で更新する
- [ ] 5.2 `openspec validate`、targeted test、`make test` / `make detekt` / `make build` を実行して記録する
