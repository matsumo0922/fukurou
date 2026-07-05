---
name: fukurou-llm-daemon-log-audit
description: "Fukurou repo-local skill for read-only production LLM daemon and paper trading log audits. Use when the user asks about fukurou LLM daemon behavior, paper trading status, fail-closed / NO_TRADE semantics, daemon chronology, scheduler skip reasons, or asks to continue the previous day's timeline table."
---

# Fukurou LLM Daemon Log Audit

Fukurou production の LLM daemon と paper trading 状態を、API と PostgreSQL の read-only query だけで確認する。これは Fukurou 固有の repo-local skill なので、汎用 Skills repo や公開汎用 skill へ移さない。

## Safety Rules

- 実装変更、DB write、deploy、container restart は行わない。
- secret 値を stdout・PR・issue・最終報告に出さない。`.env` や token を表示しない。
- DB は必ず `BEGIN READ ONLY; ... COMMIT;` で読む。`UPDATE` / `INSERT` / `DELETE` / DDL は使わない。
- `docker exec` は production の `fukurou-postgres` container 内 env を使い、password 値を外に出さない。
- shell command は `rtk` prefix を使う。script 実行は `rtk bash ./.codex/skills/...` を使う。
- crypto bot の結果は投資助言として書かない。観測値、判断理由、ledger 事実に分けて説明する。

## Quick Workflow

1. fukurou repo の `AGENTS.md` と `~/.codex/RTK.md` を確認する。
2. API で production の現在値を確認する。

```bash
rtk scripts/prod-curl "/revision" -fsS
rtk scripts/prod-curl "/health/ready" -fsS
rtk scripts/prod-curl "/evaluation/summary?from=<JST-date>&to=<JST-date>" -fsS
rtk scripts/prod-curl "/evaluation/costs?from=<JST-date>&to=<JST-date>" -fsS
```

3. 時系列が必要なら helper script を使う。

```bash
rtk bash ./.codex/skills/fukurou-llm-daemon-log-audit/scripts/query-fukurou-llm-daemon-log.sh \
  --since "2026-07-04 23:57:27+09"
```

`FUKUROU_PROD_SSH_HOST` と `FUKUROU_POSTGRES_CONTAINER` で接続先を上書きできる。既定は `dxp4800plus` / `fukurou-postgres`。

4. `RUN|...` を時系列表へ変換する。`SKIP|...` は daemon が起動しなかった判断として別にまとめる。
5. `PAPER|...`、`RISK|...`、`LEDGER|...` から paper trading の実取引有無を結論づける。

## Interpreting Rows

- `NO_TRADE_DECISION`: Proposer が正常終了し、`decisions` に `NO_TRADE` を保存した。理由・p・expectedR を説明する。
- `NO_TRADE_AUDITED`: 判断保存まで到達せず、fail-closed として `NO_TRADE_EXIT` 監査だけを残した。`proposer_missing_decision`、`exit=1`、`FAILED_TO_START`、`authFailureSuspected=true` などを原因として説明する。
- `PAPER_ENTRY_PLACED`: paper order / position / execution を追加確認し、発注・約定・STOP/TP 保護の状態を分けて報告する。
- `RUNNING`: run がまだ終わっていない。少し待って再確認してから断定する。
- `max_invocations_per_hour_exceeded`: scheduler が起動上限で skip した。障害ではなく cap による抑制として扱う。

## Report Shape

最終報告はこの順にする。

1. 確認時刻、対象期間、revision / readiness。
2. 全体結論: daemon が回っているか、fail-closed があるか、paper entry があったか。
3. 時系列表: JST 時刻、trigger、status、p / expectedR、判断要約。
4. skip 一覧: reason と時間帯。大量なら連続区間にまとめる。
5. paper ledger: cash、BTC、equity、drawdown、hardHalt、orders / positions / executions / intents / falsifications。
6. 注意点: cost、auth failure、manual run 混入、run count と API count の差分など。

## Decision Summary Heuristics

- `reason` に「高値追い」「RSI過熱」「抵抗」「押し目反発未確認」が並ぶ場合は、「上昇トレンドは認めつつ、入口品質が悪く見送り」と要約する。
- `reason` に「RSI50割れ」「MACD弱気転換」「下落中」「安値更新」が並ぶ場合は、「短期モメンタム悪化でロング優位なし」と要約する。
- `expectedR` が正でも `NO_TRADE` の場合は、期待R単体ではなく、entry trigger・STOP構造・過熱・ブレイク確認不足で否決されたことを説明する。
- manual run が混ざったら daemon cadence と分けて書く。
