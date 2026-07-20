---
name: fukurou-llm-daemon-log-audit
description: "Fukurou repo-local skill for read-only production LLM daemon and paper trading log audits. Use when the user asks about fukurou LLM daemon behavior, paper trading status, fail-closed / NO_TRADE semantics, daemon chronology, scheduler skip reasons, or asks to continue the previous day's timeline table."
---

# Fukurou LLM Daemon Log Audit

Fukurou production の LLM daemon と paper trading 状態を、API と PostgreSQL の read-only query だけで確認する。これは Fukurou 固有の repo-local skill なので、汎用 Skills repo や公開汎用 skill へ移さない。

正本は `.codex/skills/fukurou-llm-daemon-log-audit/` で、`.claude/skills/fukurou-llm-daemon-log-audit/SKILL.md` は正本 SKILL.md への symlink。Codex / Claude Code どちらの agent からも同じ手順・同じ script を使う。特定 agent 固有の CLI や設定に依存する記述は避ける。

## Safety Rules

- 実装変更、DB write、deploy、container restart は行わない。
- secret 値を stdout・PR・issue・最終報告に出さない。`.env` や token を表示しない。
- DB は必ず `BEGIN READ ONLY; ... COMMIT;` で読む。`UPDATE` / `INSERT` / `DELETE` / DDL は使わない。
- `docker exec` は production の `fukurou-postgres` container 内 env を使い、password 値を外に出さない。
- shell / script は各 agent の RTK 設定に従って実行する（Codex / Claude Code とも hook が rtk へ自動 rewrite する）。手順内のコマンドは repo root からの相対パスで書く。
- crypto bot の結果は投資助言として書かない。観測値、判断理由、ledger 事実に分けて説明する。

## Quick Workflow

1. fukurou repo の `AGENTS.md`（共通ルールは `CLAUDE.md` / `AGENTS.md` から辿れる）を確認する。
2. API で production の現在値を確認する。

   ```bash
   scripts/prod-curl "/revision" -fsS
   scripts/prod-curl "/health/ready" -fsS
   scripts/prod-curl "/evaluation/summary?from=<JST-date>&to=<JST-date>" -fsS
   scripts/prod-curl "/evaluation/costs?from=<JST-date>&to=<JST-date>" -fsS
   ```

3. 時系列が必要なら helper script を使う。

   ```bash
   .codex/skills/fukurou-llm-daemon-log-audit/scripts/query-fukurou-llm-daemon-log.sh \
     --since "2026-07-04 23:57:27+09"
   ```

   `FUKUROU_PROD_SSH_HOST` と `FUKUROU_POSTGRES_CONTAINER` で接続先を上書きできる。既定は `dxp4800plus` / `fukurou-postgres`。

4. `RUN|...` を時系列表へ変換する。`LIFECYCLE|...` は TTL cancel / EXIT / ADJUST_PROTECTION の runner 決定論的副作用として該当 run に紐づける。`SKIP|...` は daemon が起動しなかった判断として別にまとめる。
5. `PAPER|...`、`RISK|...`、`LEDGER|...` から paper trading の実取引有無を結論づける。

## Interpreting Rows

- `NO_TRADE_DECISION`: Proposer が正常終了し、`decisions` に `NO_TRADE` を保存した。理由・p・expectedR を説明する。
- `NO_TRADE_AUDITED`: 判断保存まで到達しない、または EXIT / ADJUST_PROTECTION の対象が曖昧・不正で fail-closed し、`NO_TRADE_EXIT` 監査を残した。`proposer_no_tool_calls` は Proposer が process failure / `cliErrorReported=true` / `authFailureSuspected=true` なしで完了し、判断未保存かつ許可済み tool call 0 件の状態として説明する。CLI が error を報告した場合は `cliErrorReported=true` と `proposer_missing_decision`、認証失敗が疑われる場合は `authFailureSuspected=true` と `proposer_missing_decision` を原因として説明する。`exit=1`、`FAILED_TO_START`、`exit_target_ambiguous`、`adjust_protection_invalid_take_profit_price` なども原因として説明する。
- standard material snapshot failure は `RUNNER_PHASE_COMPLETED` の `phase=standard_material_snapshot` と `failureStage`（`CAPTURE` / `VALIDATION` / `HASH_SERIALIZATION` / `PERSISTENCE`）、stage別 `failureCode`、material/run manifest、`llm_runs.terminal_cause` を突合する。stage field がない旧 `STANDARD_SNAPSHOT_UNAVAILABLE` は `UNKNOWN` とし、新stageへ推定変換しない。例外message、prompt、raw market/account、stack trace、secretを根拠として要求しない。
- `PAPER_ENTRY_PLACED`: paper order / position / execution を追加確認し、発注・約定・STOP/TP 保護の状態を分けて報告する。
- `PAPER_EXIT_EXECUTED`: runner が EXIT decision を `close_position` または `cancel_order` に写像した。`LIFECYCLE|...|exit_execution|...` と paper ledger の positions / orders / executions を確認し、position close と resting entry cancel を分けて説明する。
- `PAPER_PROTECTION_UPDATED`: runner が ADJUST_PROTECTION decision を `update_protection` に写像した。`LIFECYCLE|...|adjust_protection_execution|...` と position の STOP / virtual TP を確認し、STOP が維持され TP だけ更新されたことを説明する。
- `RUNNING`: run がまだ終わっていない。少し待って再確認してから断定する。
- `LIFECYCLE|...`: `DECISION_LIFECYCLE_COMPLETED` の監査行。`phase` が `stale_resting_entry_ttl_sweep` なら `expiredOrderCount`、`cancelSuccessCount`、`cancelFailureCount`、`canceledOrderIds`、`failedOrderIds`、`failureSummaries` で TTL cancel の部分成功・失敗を確認する。`exit_execution` なら close / cancel / fail-closed、`adjust_protection_execution` なら protection update / fail-closed を読む。fail-closed は `reason` と `evidence` を根拠にする。
- `max_invocations_per_hour_exceeded`: scheduler が起動上限で skip した。障害ではなく cap による抑制として扱う。
- `RISK|...` の `state`: `RUNNING` 以外（soft halt / hard halt）なら停止中。`hard_halt=true` は全取引停止、soft halt は縮小運用。`halt_reason` を添えて説明する。
- `LEDGER|...` の各 count は累計値（対象期間フィルタなし）。「この期間に取引があったか」は RUN 行の `PAPER_ENTRY_PLACED` と executions 累計の増減で判断する。

## Report Shape

最終報告はこの順にする。

1. 確認時刻、対象期間、revision / readiness。
2. 全体結論: daemon が回っているか、fail-closed があるか、paper entry があったか。
3. 時系列表: JST 時刻、trigger、status、p / expectedR、判断要約。
4. skip 一覧: reason と時間帯。大量なら連続区間にまとめる。
5. risk / paper ledger: hard_halt、state（soft/hard halt）、halt_reason、cash、BTC、equity、drawdown、orders / positions / executions / intents / falsifications / safety_violations（累計）。
6. 注意点: cost、auth failure、manual run 混入、run count と API count の差分など。

## Decision Summary Heuristics

- `reason` に「高値追い」「RSI過熱」「抵抗」「押し目反発未確認」が並ぶ場合は、「上昇トレンドは認めつつ、入口品質が悪く見送り」と要約する。
- `reason` に「RSI50割れ」「MACD弱気転換」「下落中」「安値更新」が並ぶ場合は、「短期モメンタム悪化でロング優位なし」と要約する。
- `expectedR` が正でも `NO_TRADE` の場合は、期待R単体ではなく、entry trigger・STOP構造・過熱・ブレイク確認不足で否決されたことを説明する。
- manual run が混ざったら daemon cadence と分けて書く。

## Maintenance

fukurou は変化が速く、helper script は table 名・列名・event_type・payload JSON path を直書きしている。schema が変わると `ON_ERROR_STOP=1` で query が落ちるので、silent に誤った結果を返すことはない。落ちたら以下の正本を見て script を直す。

- table / 列: `trading/src/main/kotlin/me/matsumo/fukurou/trading/persistence/TradingTables.kt`
- event_type / runner status: `trading/.../audit/CommandEvent.kt`、`trading/.../runner/OneShotLlmRunner.kt`
- API endpoint / パラメータ: `fukurou/src/main/kotlin/me/matsumo/fukurou/EvaluationRoutes.kt`、`/openapi.json`（route-local `.describe {}` が正本）

新しい table / 列（例: risk_state の soft halt `state`）が運用上重要になったら、script の対応 SELECT と本 SKILL の Report Shape を同じ差分で更新する。
