## Context

最新 main の `make test` は macOS で clean-green だが、GitHub-hosted Linux runner の直近三回で同じ三テストが失敗する。失敗は production code の回帰ではなく、(1) 300秒の JDBC socket timeout に接近する oversized population fixture、(2) supervisor ACK を返さない shell process と timeout 同時刻の PID wait、(3) macOS の長い temp path だけで成立する socket fallback assumption に起因する。

## Goals / Non-Goals

**Goals:**

- Linux CI と macOS local の両方で同じ production contract を検証する。
- population bound、process-tree exit proof、gateway startup fail-closed の意味を弱めず、test を bounded time で完了させる。
- fixture artifact を成功・失敗の両経路で cleanup する。

**Non-Goals:**

- product/runtime code、production supervisor protocol、gateway path selection の変更。
- PostgreSQL の entity limit、JDBC timeout、repository query の変更。
- failing quality gate の retry による回避。

## Decisions

### D1: scoped materialization と global population bound を分離する（agent 仮決め）

正常経路は20,002件の period populationのうちcurrent scopeに属する1件だけで attribution と prior PnL を確認する。oversized rejectionは同じglobal period populationを使い、三表の全行をcurrent scopeへ更新して20,002件のscoped tradeをmaterializeしない。これによりscope filterとentity limitを維持しながら、oracleはtransport timeoutではなくtyped `ENTITY_LIMIT` のままになる。

代案の JDBC timeout 延長は test をさらに遅くし、connection bound 契約を弱めるため採用しない。repository query の最適化は production code を変更する別スコープになるため採用しない。

### D2: recovery child は production supervisor protocol を模倣する（agent 仮決め）

fixture は root process が timeout signal を受けたときに supervisor ACK 用 exit code を返し、descendant の終了を待つ。PID file は bounded poll helperで観測し、process timeout と競合しない前倒し deadline を持つ。

代案の期待値を `UNCERTAIN` に変える方法は、test が証明すべき successful reap contract を失うため採用しない。

### D3: production と同じ socket path を test が導出する（agent 仮決め）

gateway-start fixture は manifest sibling と `/tmp` fallback の選択規則を production helperと共有または同じ公開境界から取得し、実際に bind される exact path を妨害する。artifact cleanup は `try/finally` に置く。

代案の temp directory 名を意図的に長くする方法は fallback branch しか検証せず、Linux の通常 sibling branch を再び未検証にするため採用しない。

## Risks / Trade-offs

- [Risk] 全件 scope 更新の除去で filter coverage を失う → 1件 scoped + 20,001件 scope外を維持し、正常 scoped 集計と oversized typed rejection を独立 assertion として確認する。
- [Risk] test が production path logic を複製して再び drift する → 既存 helper が使える場合は共有し、使えない場合は exact selected path を観測して妨害する。
- [Risk] shell fixture が production supervisor より単純になる → ACK exit と descendant reap という当該 test の契約だけを明示して検証する。

## Migration Plan

test-only change として通常 PR を merge し、latest main の deploy quality workflow を再dispatchする。rollback は commit revert であり、DB/runtime migration はない。

## Open Questions

なし。
