## Issue #189 closure matrix

この表を PR description の Issue #189 evidence source とする。operator evidence が未取得で、deploy required hook も未実装のため、Issue #189 は閉じない。

| Issue #189 DoD | Prior evidence | Foundation evidence | This change | Operator evidence | Status |
|---|---|---|---|---|---|
| pinned CLI auth smokeを各phase×3 | versioned renderer/parser、direct pinned CLI | exact-image CLI pinとidentity probe | 4 phase matrix、no-tool/MCP policy、3回限定qualification。Codex served modelは未観測 | final digest のsafe qualification結果が必要 | 未完了 |
| timeout/cancel 100回でorphan 0 | bounded process-tree termination、cleanup terminal | exact-image timeout/cancel各100件 | phase timeoutとcleanup fail-closed | 同一digest foundation成功が必要 | final digest証跡待ち |
| semantic/process/cleanup terminal分離 | 3 terminalの独立audit projection | cleanup acknowledgement | primary codeとcleanup statusを直交して報告 | 不要 | 完了 |
| sandbox/tool inventory | canonical `ToolPolicy` とadapter | launcher、MCP/DB/secret/process isolation | app UID、read-only rootfs、write annotation、Claude `submit_decision`、Codex auto-approved `submit_falsification` | 同一digest qualificationが必要 | final digest証跡待ち |
| auth/rate/quota/Codex typed failure | provider-neutral category | safe artifact scan | raw output非表示のsafe code | real failureもsafe resultだけを記録 | 完了 |
| Codex price換算を実費と分離 | versioned static catalogとevaluation response | 対象外 | 変更なし | 不要 | 完了 |
| Codex session retentionと削除SLO | per-run tmpfs、terminal cleanup、quarantine | nested session cleanup | phaseごとの独立copyとcleanup | 同一digest qualificationが必要 | final digest証跡待ち |
| deploy時CLI smokeを常設 | なし | `FOUNDATION_PREFLIGHT_V1`のみ | reusable acceptance harness。deploy wiringなし | deploy hook change後に必要 | 未完了 |

## Validation ledger

| Evidence | Result |
|---|---|
| Kotlin phase matrix / model / schema / safe failure tests | PASS。production renderer/parser回帰とtargeted detektもPASS |
| harness fake-Docker isolation / repetition / lifecycle selftest | PASS。fixture tool inventory、safe output、single digest resolutionを含む |
| OpenSpec validation | PASS |
| final exact-image provider qualification×3 | operator実行待ち。Codex served model identity は provider output 非報告のため未検証 |
| final `make test` / `make detekt` / `make build` | PASS。main rebase後の同一treeで完走 |
