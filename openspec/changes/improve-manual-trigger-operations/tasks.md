## 1. MANUAL reserve admission

- [x] 1.1 `LlmLaunchReservationRepository.reserveRejection()` で `MANUAL` を `protectedEntry` と `protectedStop` の保護対象から除外し、hard cap より後の reserve 判定だけを変更する
- [x] 1.2 `ENTRY_FILL` / `STOP_PROXIMITY` 相互の reserve 保護、reflection/evaluation headroom、他の admission gate に変更がないことを差分で確認する

## 2. Reservation regression tests

- [x] 2.1 hourly / daily の各 reserve 保護域で `FLAT_HEARTBEAT` は対応する reserve reason で拒否され、同じ usage の `MANUAL` は予約される repository test を追加する
- [x] 2.2 hourly / daily の各 hard cap へ到達した状態で、`MANUAL` が対応する `MAX_INVOCATIONS_PER_*` reason で拒否される repository test を追加する
- [ ] 2.3 `LlmLaunchReservationRepositoryTest` の対象テストを実行し、既存 hourly / daily reserve 回帰も通ることを確認する

## 3. Production operations documentation

- [x] 3.1 `docs/llm-obsidian-production-setup.md` の Codex fallback login を `docker exec -it --user 10001 ... codex login --device-auth` へ更新する
- [x] 3.2 login 前後の `/tmp/fukurou-cli-home/.codex/auth.json` mtime 確認、同じ UID/home の `codex login status`、UID 0 login の危険性を現在形で追記する
- [x] 3.3 現行 image は `USER appuser` であることを踏まえ、`--user` 省略が現在 root を選ぶとは記述せず、撤去済み supervisor の spawn refusal restart 手順を追加しない
- [x] 3.4 `docs/mcp-runtime.md` の起動予算へ、`MANUAL` は unused critical reserve を使用できるが hourly / daily hard cap には従うことを追記する
- [x] 3.5 `MANUAL`、reserve reason、`codex login`、`auth.json`、UID 10001 で `docs/` と `README.md` を検索し、今回の変更で誤りになる記述だけを更新する

## 4. Validation and delivery

- [ ] 4.1 IntelliJ MCP の `get_file_problems` で変更した Kotlin ファイルの error / warning を確認する
- [ ] 4.2 `make test` と `make detekt` を実行する
- [ ] 4.3 `openspec validate improve-manual-trigger-operations --strict` を実行し、proposal・design・specs・tasks の整合を確認する
- [ ] 4.4 code・tests・docs・OpenSpec を単一 PR にまとめ、PR description に `ドキュメント影響: あり（docs/llm-obsidian-production-setup.md, docs/mcp-runtime.md）` と hard cap 非変更を明記する
