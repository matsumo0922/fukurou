## Context

Issue #189 の renderer、versioned output adapter、typed failure、process cleanup、session retention、cost attribution は先行 PR で実装済みである。既存 `FOUNDATION_PREFLIGHT_V1` と `scripts/mcp-credential-isolation-check` は exact image、fixed launcher、MCP tool matrix、secret/process isolation を offline fixture で検証する一方、実 Claude/Codex credential と provider endpoint は使用せず、deploy bundle も foundation hook だけを要求する。

`DeploymentPreflightMain` は supervisor PID 1 が JVM child を同期 wait する構造であるため、その child から同じ supervisor の launch socketを使うと deadlockする。実 provider canary は一回限りの JVM preflight childへ詰め込まず、candidate imageを通常の fixed-launch serviceとして起動した別 harnessから実行する必要がある。

本変更は個人運用の実験システム向けである。新しい network proxy、credential broker、DB schema、汎用 canary framework は導入せず、#189 を閉じるための小さな phase matrixだけを常設する。

## Goals / Non-Goals

**Goals:**

- exact candidate image に pin された Claude Code 2.1.199 / Codex 0.142.5 を実 provider credential で起動する。
- production の `DefaultLlmCommandRenderer`、fixed LLM launcher、`ShellProcessRunner`、`DefaultLlmOutputParser` を同じ invocation で通す。
- Claude `PRE_FILTER`、Claude `PROPOSER`、Codex `FALSIFIER`、Claude `REFLECTION` の no-MCP / MCP matrixを検証する。
- candidate activation 前に auth、output envelope、configured/observed model、MCP tool resolution、timeout、cleanupを fail closed で判定する。
- production credential source、DB、vault、trading state、raw provider outputを canary artifactやログへ出さない。

**Non-Goals:**

- #154 pre-filter activation、daemon cadence、role assignmentの変更。
- provider trafficのproxy、egress allowlist、sidecarなどの新しいnetwork topology。
- production DBまたは実market dataを使うcanary。
- SafetyFloor、paper/live order lifecycle、DB migrationの変更。
- 既存 foundation canary が担う full MCP call matrix、DB role、read/write/secret/process isolationの再実装。
- provider品質や投資判断の評価。

## Decisions

### 1. Signed hook と実 provider harness を二段に分ける

deploy bundle は `FOUNDATION_PREFLIGHT_V1` に続いて `CLI_AUTH_PREFLIGHT_V1` を required hook として署名する。candidate の `--canary-preflight cli-auth` は署名、candidate SHA/digest/catalog binding、closed pre-filter barrier、DB非搭載を検証する。その成功後だけ、root executor がbundleにhash固定された `scripts/cli-auth-acceptance-check` を exact digestへ実行する。

foundation と同様に「candidate自身がsigned hookを認識すること」と「外部harnessが実際のlifecycleを検証すること」を分ける。JVM preflight childからfixed launcherを呼ぶ案はsupervisorの同期waitとlaunch listenerが競合するため採用しない。

### 2. acceptance-only supervisor mode を追加する

runtime supervisor にPID 1専用の `--acceptance-service` を追加する。このmodeはlaunch socketとjob cleanup loopだけを起動し、Ktor application、control socket、DB reconciliationを起動しない。通常runtimeと同じprovider process spawn、timeout/cancel、acknowledged cleanupを再利用する。

任意commandを受ける汎用serviceにはしない。image内のfixed launcher protocolだけを受け、container終了時に全AI jobを回収する。これによりproduction DBを用意せずproduction launcher pathを検証できる。

### 3. canary driver は production renderer/parser を直接再利用する

`CliAcceptanceCanaryMain` は4 phaseの固定tableから `LlmInvocationRequest` を生成し、次を実行する。

- `PRE_FILTER`: Claude、`claude-haiku-4-5-20251001`、no-MCP、empty canonical policy
- `PROPOSER`: Claude、`claude-haiku-4-5-20251001`、fixture MCP、canonical Proposer policy
- `FALSIFIER`: Codex、`gpt-5.5`、fixture MCP、canonical Falsifier policy、LOW effort
- `REFLECTION`: Claude、`claude-haiku-4-5-20251001`、no-MCP、empty canonical policy

各requestは `DefaultLlmCommandRenderer` でper-run auth/configを生成し、fixed launcherを通して `ShellProcessRunner` で起動し、`DefaultLlmOutputParser` でpinned output envelopeを解析する。provider failure、非0終了、timeout、schema drift、model mismatch、semantic marker不一致、cleanup failureのいずれもrun失敗とする。

CLI default modelやproduction DB runtime configには依存しない。canary pinはcode-owned constantとし、image pin / adapter pin / test期待値を同じ変更で更新する。

### 4. MCP validation はdata-free fixtureに限定する

image内にstdio MCP fixture serverを追加する。Proposer/Falsifierのcanonical tool名を列挙するが、副作用や外部I/Oを持たず、指定されたprobe toolへrun固有nonceを返す。promptはprobe toolを1回呼び、返されたnonceを最終responseに含めるよう要求する。driverはnonce一致を確認するため、単なるMCP config読込ではなくtool resolutionとcall completionまで証明できる。

fixtureはproduction fixed MCP launcherを置き換える責務を持たない。fixed MCP launcher、phase manifest、DB role、全required callは直前の `FOUNDATION_PREFLIGHT_V1` が検証済みであり、CLI auth canaryはprovider互換性だけを追加する。

### 5. credential source は既存 volume をread-onlyで参照する

harnessはproduction composeの `llm-auth` volumeだけを `/tmp/fukurou-cli-home:ro` にmountする。DB password、`.env`、vault、Docker socket、production network/volumeはmountしない。containerはread-only rootfs、production相当capability、private tmpfs、provider outbound用の通常bridge networkだけを持つ。

rendererはClaude/Codexの必要auth fileだけをper-run tmpfsへcopyする。harnessはsource fileの存在とread-only mountを検証し、実行前後のsource digestが不変であることを比較するが、path、digest、credential contentをログへ出さない。raw stdout/stderrもdriver内だけで解析し、安全なphase/result codeだけを出力する。

### 6. merge qualification とdeploy gateの回数を分ける

harnessは `--runs 1|3` だけを受ける。merge qualificationはexact final imageで `--runs 3` を実行し、4 phaseすべての連続3成功を要求する。production deployはprovider消費とdeploy時間を抑えるため `--runs 1` をrequired hookとして毎回実行する。

GitHub-hosted CIにはcredentialを渡さず、contract/unit/offline exact-image testsだけを実行する。実 provider qualificationはoperator credentialを持つ環境で行い、成功をPR evidenceとして記録する。

### 7. deploy contract は両hook成功までmutationを許可しない

signed bundle、installed catalog、executor validation、dispatch ledgerは `CLI_AUTH_PREFLIGHT_V1` を必須化する。hook順序は foundation、CLI auth とし、どちらかが失敗した場合はproduction compose更新、maintenance解除、candidate activationへ進まない。既存runtimeはそのまま維持される。

## Risks / Trade-offs

- [Providerの一時障害やquotaでdeployが止まる] → typed safe reasonでfail closedし、既存runtimeを維持する。自動retryは各phase 1回に限定し、operatorがdeployを再実行する。
- [実modelの応答がprobe指示に従わずflakeする] → 短い固定prompt、単一nonce、LOW effortを使い、merge前に各phase×3で安定性を確認する。意味的なJSON判断は要求しない。
- [通常bridgeはprovider以外へのoutboundも可能] → containerにcredential source以外をmountせず、MCP fixtureにnetwork機能を持たせない。provider egress proxyは本issueの範囲外とする。
- [read-only auth sourceでもcopy後にproviderがtoken refreshする] → refreshはper-run copyに閉じ、sourceへ書き戻さない。sourceが失効している場合はdeployを止め、既存WebUI login flowで更新する。
- [hook追加でdeploy時間とprovider利用量が増える] → deployは4 invocationを各1回だけ実行し、cheap Claude pinとLOW effortを使う。
- [supervisor acceptance modeがruntime権限を広げる] → PID 1かつ固定argumentでのみ起動し、application/DB/controlを持たず、既存launcher protocol以外を追加しない。

## Migration Plan

1. offline contract/unit testsとexact-image fixture testを通す。
2. final candidate imageをbuildし、operator credential環境で `--runs 3` を通す。
3. harness、catalog、executor、workflow bundleを同じcommitで配布する。
4. deploy時にfoundation、CLI authの順でpreflightし、両方成功後だけproduction mutationへ進む。
5. rollbackでは旧main SHAを明示的に再deployし、旧bundle/catalogのrequired hook setへ戻す。credential volumeとproduction DBにはmigrationがない。

## Open Questions

なし。
