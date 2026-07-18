## Context

Issue #189 の renderer、versioned output adapter、typed failure、process cleanup、session retention、cost attribution は先行 PR で実装済みである。既存 `FOUNDATION_PREFLIGHT_V1` と `scripts/mcp-credential-isolation-check` は exact image、fixed launcher、MCP tool matrix、secret/process isolation をoffline fixtureで検証する一方、実Claude/Codex credentialとprovider endpointは使用しない。

実provider smokeを現行signed deploy hookへ直ちにrequired化すると、content hashで固定されたinstalled executor/harnessとmonotonic capability catalogによりhook導入前SHAへのhistorical rollbackを破壊する。このchangeはacceptance runtimeとmerge qualificationだけを実装し、deploy wiringはrollback contractと両立する別changeへstage-outする。

## Goals / Non-Goals

**Goals:**

- exact candidate image にpinされたClaude Code 2.1.199 / Codex 0.142.5を専用credentialで起動する。
- productionの `DefaultLlmCommandRenderer`、`ShellProcessRunner`、`DefaultLlmOutputParser` を同じinvocationで通す。
- Claude `PRE_FILTER`、Claude `PROPOSER`、Codex `FALSIFIER`、Claude `REFLECTION` のno-MCP / MCP matrixを検証する。
- auth、output envelope、configured model、Claude observed model、MCP tool resolution、timeout、cleanupをfail closedで判定する。
- production credential source、DB、vault、trading state、raw provider outputをcanary artifactやログへ出さない。

**Non-Goals:**

- deploy executor、signed bundle、capability catalog、`CLI_AUTH_PREFLIGHT_V1` required hookへの接続。
- hook導入前SHAを含むsigned historical rollback contractの変更。
- fixed launcher、DB role、full MCP call matrix、process-tree isolationの再検証。これらは同一digestで実行するfoundation canaryの責務とする。
- #154 pre-filter activation、daemon cadence、role assignmentの変更。
- provider trafficのproxy、egress allowlist、sidecarなどの新しいnetwork topology。
- production DB、実market data、SafetyFloor、paper/live order lifecycle、DB migrationの変更。

## Decisions

### 1. 実provider acceptanceをdeploy wiringから分離する

本changeは `scripts/mcp-credential-isolation-check` に `--qualification --runs 3 --reuse-image <repository@sha256:digest>` と `--cli-acceptance --runs 1 --reuse-image <repository@sha256:digest>` を追加する。既存offline foundation modeの意味と既定動作は変えない。

merge qualification modeはmutable tagとbare image IDを拒否し、final imageのimmutable repository digestをDockerから一度だけresolveする。同じscript processと同じresolved referenceで既存foundation canaryを完了した後、実provider matrixを3回実行する。safe evidenceにはresolved digestと両stageの結果を1組だけ出力するため、別imageの成功を合成できない。

`--cli-acceptance --runs 1` は同じimmutable referenceに対する短いoperator smokeとして提供するが、foundationとの合成やmerge qualificationとは表示しない。production deployからも自動起動しない。これによりrollback contractを暗黙に変更せず、次のdeploy wiring changeが同じharnessを再利用できる。

### 2. 専用credential sourceを使う

harnessはoperatorが事前loginしたDocker volume `llm-canary-auth` だけを `/canary-auth:ro` にmountする。production composeの `llm-auth` volumeはmountしない。rendererへ渡す環境はClaude/Codexのcanary auth source path、fixed CLI pin、private tmpfsだけであり、DB password、`.env`、vault、Docker socket、production network/volumeを含めない。

providerがrefresh tokenをrotationしても影響は専用canary credentialに限定される。harnessはphaseごとにsourceから独立copyを作り、3連続matrixでsourceの再利用可能性も検証する。source filesystem digestの不変だけをremote credential有効性の証明とは扱わない。途中で専用credentialが失効した場合はqualificationを止め、operatorがcanary専用loginを更新する。

### 3. driverはapp UIDでproduction renderer/parserを再利用する

`CliAcceptanceCanaryMain` はcontainer内でUID/GID `10001:10004`として実行し、4 phaseの固定tableから `LlmInvocationRequest` を生成する。command templateはimage内のpinned `/usr/local/bin/claude` と `/usr/local/bin/codex` を直接指定する。別container内にproduction resourceが存在しないため、実provider側のfixed launcher経由実行は重複させない。fixed launcher、UID分離、process tree、cleanup acknowledgementは同じexact digestに対するfoundation canaryで検証する。

phase matrixは次のとおりとする。

- `PRE_FILTER`: Claude、`claude-haiku-4-5-20251001`、no-MCP、empty canonical policy
- `PROPOSER`: Claude、`claude-haiku-4-5-20251001`、fixture MCP、canonical Proposer policy
- `FALSIFIER`: Codex、`gpt-5.5`、fixture MCP、canonical Falsifier policy、LOW effort
- `REFLECTION`: Claude、`claude-haiku-4-5-20251001`、no-MCP、empty canonical policy

各requestをrender、run、parseし、provider failure、非0終了、timeout、schema drift、semantic marker不一致、cleanup failureをrun失敗とする。Claudeはsupported outputが報告するobserved modelもpinと一致させる。Codex 0.142.5はobserved modelを報告しないため、configured `-m gpt-5.5` とexact CLI/image pinだけを保証し、observed identityは `NOT_REPORTED_BY_PROVIDER` のままとする。

### 4. MCP validationはdata-free fixtureに限定する

image内のstdio MCP fixture serverはProposer/Falsifierのcanonical tool名を列挙し、副作用や外部I/Oを持たないprobe結果を返す。run固有nonceはpromptへ含めずfixtureだけが返し、driverはfinal responseにnonceが含まれることを検証する。これは少なくとも1回のtool resolutionとcall completionを検出する互換性probeであり、悪意あるmodelに対するproofやexact call countは保証しない。

production fixed MCP launcher、phase manifest、DB role、全required callはfoundation canaryが検証する。merge qualification evidenceは同一exact digestについてfoundation successと実provider acceptance successを併記する。

### 5. harnessはresourceとlogを最小化する

acceptance containerはread-only rootfs、cap-drop ALL、no-new-privileges、private tmpfs、pids limit、provider outbound用の通常bridge networkだけを持つ。credential volume以外のhost mountとproduction networkを禁止する。matrix全体のdeadlineは30分、各phaseは120秒とし、`--runs 3` の最大12 invocationを直列実行する。

raw stdout/stderrはdriver内でversioned parserへ渡すだけで出力しない。top-level failureはallowlist済みcodeへ変換し、phase、iteration、adapter version、safe resultだけを表示する。credential path/content/digest、prompt、provider response、exception messageは表示しない。終了時はper-run config/homeを全削除し、container/temporary volumeを残さない。

### 6. 差分hard stopを機械的に守る

initial design commit以後のOpenSpec planning artifactと回収済みarchiveを除き、code、test、script、docsのadded/deleted line合計を1,100行以下に保つ。実装中に超える見込みまたは実測超過が出た時点でSTOPし、fixture/testまたはharness責務を次changeへstage-outする。既存巨大scriptの無関係な整形は行わない。

### 7. Issue #189 closure evidenceを明示する

このchangeのPR descriptionにはIssue #189全DoDのclosure matrixを載せる。先行PRのtest/evidence、既存foundation canary、本changeのoffline tests、operator real-provider×3 evidence、未完了のdeploy required hookを区別する。本change merge後もdeploy hookが未完了なのでIssue #189はopenのままとする。

## Risks / Trade-offs

- [専用credentialのlogin保守が増える] → production credentialを壊さないことを優先し、WebUI/production volumeと分離したoperator手順をdocsへ記載する。
- [provider一時障害やquotaでqualificationが止まる] → safe typed reasonでfail closedし、自動retryせずoperatorが再実行する。
- [実modelがprobe指示に従わずflakeする] → 短い固定prompt、single nonce、LOW effortを使い、各phase×3で安定性を確認する。
- [通常bridgeはprovider以外へのoutboundも可能] → production resourceをmountせず、fixtureにnetwork機能を持たせない。egress proxyは範囲外とする。
- [direct CLIはfixed launcher実経路ではない] →同一digestのfoundation canaryと合成し、責務をPR evidenceで明示する。新しいsupervisor modeは追加しない。

## Migration Plan

1. offline contract/unit testsとexact-image fixture failure testsを通す。
2. operatorが専用 `llm-canary-auth` volumeへClaude/Codex loginを用意する。
3. final immutable repository digestを指定した単一qualification invocationでfoundation canaryを1回、実provider matrixを3回連続で実行する。
4. PR evidenceへsafe resultとIssue #189 closure matrixを記録する。
5. merge後もruntime/deploy contractは変わらない。rollbackは通常のcode rollbackだけで完了する。

## Open Questions

なし。
