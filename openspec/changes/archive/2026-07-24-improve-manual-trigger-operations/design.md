## Context

`launchBudgetRejection()` は rolling hourly / daily usage から hard cap 超過を先に判定し、その後 `reserveRejection()` で未使用の `ENTRY_FILL` / `STOP_PROXIMITY` reserve を保護する。現在の `reserveRejection()` は、自分自身の reserve を使う critical trigger だけを各保護判定から除外するため、`MANUAL` は `FLAT_HEARTBEAT` などの automatic trigger と同じく reserve 保護域で拒否される。

Issue #307 の incident では、失敗 run が hourly usage を消費した後、owner が credential 復旧を確認するための `POST /ops/trigger` まで reserve 保護に拒否された。LLM invocation hard cap はサブスクリプションコストと負荷を管理する運用 policy として維持する一方、owner が明示的に発行する `MANUAL` は critical reserve の保護対象から外す。

Codex の永続 auth source は compose-managed `llm-auth` volume の `/tmp/fukurou-cli-home/.codex/auth.json` であり、UID 10001 の appuser が更新する。root は `cap_drop` 後に appuser 所有ディレクトリへ credential を保存できない一方、外部の認証セッションだけを revoke し得る。現行 image は #311 により `USER appuser` で直接 Ktor を起動するため、`docker exec` の既定 user も現在は appuser であり、runtime supervisor は存在しない。したがって runbook は UID 10001 を明示して将来の image default 変更にも耐える形にし、root の危険性は `--user 0` の禁止として説明する。supervisor の spawn 拒否解除を目的とする restart note は現行構成に追加しない。

## Goals / Non-Goals

**Goals:**
- `MANUAL` を hourly / daily の `ENTRY_FILL`・`STOP_PROXIMITY` reserve 保護から除外する
- `MANUAL` にも hourly / daily hard cap と既存 admission gate を適用し続ける
- reserve 保護域で automatic trigger と `MANUAL` の結論が分かれること、および hard cap では `MANUAL` も拒否されることを回帰テストで固定する
- production Codex fallback login の実行 UID、auth source、保存確認、root 実行禁止を現在形の runbook に固定する
- LLM 起動予算の設計文書を現在の例外規則へ同期する

**Non-Goals:**
- `maxInvocationsPerHour` / `maxInvocationsPerDay` と reserve 設定値の変更
- `MANUAL` の hard cap、concurrency、maintenance、HARD_HALT、global launch gate からの免除
- `/ops/trigger` の認証・権限・wire contract の変更
- Codex 再ログインの自動化、token 失効検知、auth API の変更
- runtime supervisor や spawn refusal の復活・修正（#311 で撤去済み）
- schema、ledger、order lifecycle、paper execution semantics の変更

## Decisions

### D1. `MANUAL` の例外は reserve protection の2判定だけに限定する

**帰属:** ユーザー確認済み（Issue #307 の明示要件）

`reserveRejection()` に `manualRequest`（または同義の Boolean）を導入し、`protectedEntry` と `protectedStop` の成立条件へ `!manualRequest` を加える。`launchBudgetRejection()` の hard cap 判定順序、critical trigger 同士の reserve 関係、reflection/evaluation headroom 判定、repository の他の admission gate は変更しない。

この方法なら hourly / daily は既存の `hourly` 引数を共有して同じ規則になり、変更箇所も reserve protection の意図が見える2条件に限定できる。`MANUAL` の場合に `reserveRejection()` 冒頭から常に `null` を返す案は短いが、将来この関数へ reserve 以外の判定が追加された際にも無条件で迂回するため採用しない。

hard cap は `reserveRejection()` より前に `hourlyRemaining <= 0` / `dailyRemaining <= 0` で判定される。順序を変更しないことで、reserve 例外が hard cap 免除へ拡大しないことを構造的に維持する。

### D2. repository 境界の2テストで hourly / daily の reserve 例外と hard cap を固定する

**帰属:** agent 仮決め（Issue #307 の受け入れ条件を最小の repository 回帰へ具体化、独立反証 F-01 を反映）

`LlmLaunchReservationRepositoryTest` に次の回帰を追加する。

1. reserve 例外テストでは、hourly と daily の各 subcase で non-reserved headroom を `FLAT_HEARTBEAT` により消費する。同じ usage で次の `FLAT_HEARTBEAT` が対応する reserve reason で拒否されることを確認した後、拒否が usage を消費していない同じ repository で `MANUAL` が予約されることを確認する。
2. hard cap テストでは、hourly と daily の各 subcase で通常 trigger と `ENTRY_FILL`・`STOP_PROXIMITY` を使って対応する hard cap へ到達させ、その後の `MANUAL` が `MAX_INVOCATIONS_PER_HOUR` または `MAX_INVOCATIONS_PER_DAY` で拒否されることを確認する。

純粋関数 `launchBudgetRejection()` の直接テストではなく repository の `tryReserve()` を使い、rolling usage の集計、rejection、reservation insert まで既存の実経路で検証する。2 test の中で hourly / daily を subcase 化し、delta spec の4 Scenario を直接証明する。Issue の DoD にない exhaustive/property test は作らない。

### D3. fallback login は明示 UID と auth source mtime の前後比較を正本にする

**帰属:** ユーザー確認済み（UID 10001・mtime・root 禁止）／agent 仮決め（#311 後の現在形への補正）

`docs/llm-obsidian-production-setup.md` の Codex fallback command を次の形へ変更する。

```sh
ssh -t dxp4800plus 'docker exec -it --user 10001 fukurou-ktor codex login --device-auth'
```

login 前後で appuser が見る auth source の mtime を `stat` し、`/tmp/fukurou-cli-home/.codex/auth.json` が新規作成または更新されたことを確認する。`codex login status` も同じ UID と `CODEX_HOME=/tmp/fukurou-cli-home/.codex` を使い、別 user・別 home の状態を誤って確認しない。

runbook は次を明記する。
- 現行 image の既定 user は appuser だが、復旧手順は `--user 10001` を明示し image default に依存しない
- UID 0（`--user 0` / root）で login しない
- CLI の `Successfully logged in` 表示だけでは保存成功とみなさず、auth source の mtime 更新を確認する
- root login は credential を保存できないまま既存セッションを revoke し、認証状態を悪化させ得る

Issue 本文の「`--user` を省略すると root」という説明は #311 より前の container 構成には当てはまるが、現行 `USER appuser` には当てはまらないため、そのまま現在形へ転記しない。また supervisor は撤去済みなので、credential 修復後の spawn refusal 解除を目的とする container restart は追加しない。通常の auth source 所有権修復、forced-kill recovery、cleanup quarantine に関する既存 restart 手順は別目的なので変更しない。

### D4. current design documentation に `MANUAL` 例外を1文追加する

**帰属:** agent 仮決め（現在形ドキュメント規約への同期）

`docs/mcp-runtime.md` の起動予算説明は hard cap と critical reserve を正本としているため、`MANUAL` は unused critical reserve を使用できるが hard cap には従うことを同じ段落へ追記する。README と docs 全体を `MANUAL`、reserve reason、`codex login`、`auth.json`、UID 10001 で検索し、今回の変更で誤りになる記述だけを更新する。調査中に見つけた別 topic の stale な記述はこの change へ混ぜず報告に留める。

### D5. 実装は単一 PR にまとめる

**帰属:** agent 仮決め（1,000行目安と contract 同期に基づく配送判断）

変更は reserve 条件の局所修正、repository test 2本、運用 runbook と設計文書の同期であり、schema・API・複数 module の配線変更を含まない。OpenSpec artifact を含めても human-authored diff は 1,000 行目安を十分下回り、code・test・docs を分割すると一時的に contract が不一致になる。

したがって PR は分割せず、1 PR で code、tests、docs、OpenSpec sync を完結させる。実装時に想定外の cross-module 変更が発生して 1,000 行目安へ近づいた場合だけ、実装 PR と docs PR には分けず、独立して deploy 可能な behavior slice が成立するかを再評価する。

## Risks / Trade-offs

- [Risk] owner が複数回 `MANUAL` を発行すると、未使用の critical reserve を消費し `ENTRY_FILL` / `STOP_PROXIMITY` の保証 headroom を減らせる → Mitigation: owner-only の明示操作として許容し、hard cap と他の admission gate は維持する。runbook/仕様で reserve だけの例外であることを明記する
- [Risk] hard cap より先に manual 例外を評価すると上限を迂回する → Mitigation: `launchBudgetRejection()` の既存判定順序を変更せず、hard cap 到達時の repository 回帰テストを追加する
- [Risk] login 成功表示と実際の auth source が食い違う → Mitigation: UID 10001 を明示し、auth source mtime の前後比較と同じ UID/home の `codex login status` を必須にする
- [Risk] issue の incident 記述をそのまま現在形へ転記すると、#311 後も `docker exec` の既定 user が root、または supervisor が存在すると誤認させる → Mitigation: current Dockerfile/compose を正とし、root は明示 UID 0 として禁止し、supervisor restart note は追加しない

## Migration Plan

- schema・データ migration・runtime config activation は不要
- 通常の test/detekt/build を通した image を deploy すれば、以後の `MANUAL` reservation に新しい reserve policy が適用される
- runbook 更新は deploy 後の current image（UID 10001 appuser）を前提に使用する
- rollback は code と docs を同じ commit/PR 単位で revert し、`MANUAL` を従来の reserve 保護対象へ戻す。保存済み reservation や auth file の変換は不要

## Open Questions

なし。
