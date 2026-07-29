## Context

`DefaultLlmAuthService.providerStatus()` は credential marker file が非空の regular file として存在すれば `LOGGED_IN` を返す。marker の中身も、その credential が実際に使えるかも見ない。2026-07-23 の障害では Codex の refresh token が失効し（`refresh_token_reused` → 401）全 proposer run が失敗していたが、marker file は残っていたため `/ops/llm-auth` は `codex: logged_in`（detail: `credential marker present`）を返し続けた。

失効の事実は監査記録（`command_event_log` の `RUNNER_PHASE_COMPLETED`）には残っていた。issue #295 で導入した output-interpreted 経路により、Codex の OUTPUT_CONTRACT failure で redaction 後の stderr が保存されるため、operator は PR #300 のデプロイ後に stderr 本文を読んで原因を特定できた。つまり evidence は既に DB にあり、監視 API がそれを見ていないだけである。

issue #306（PR #324）が既にこの evidence の観測を解決している。当該障害の stderr 文言（`refresh_token_reused` / `token_expired` / `Failed to refresh token`）が `CODEX_KNOWN_AUTH_EVIDENCE_TEXTS` に追加され、`authFailureSuspected` が `primary category == AUTHENTICATION || authEvidenceObserved` へ変わった。したがって、当該障害と同形の run は既に `authFailureSuspected = true` として観測される。

本 change はその観測結果を監視 API の status へ繋ぐ部分だけを担う。文言の追跡や新しい signal は追加しない。

もう1つ、判定規則の設計を強く縛る事実がある。**LLM invocation は persistent credential source を直接使わず、per-run home への copy を使う。** `DefaultLlmCommandRenderer.copyCodexAuthFile()` は source を temp directory へ `Files.copy(..., COPY_ATTRIBUTES)` し、run 後は copy を削除するだけで source へ書き戻さない。したがって「invocation が成功した」は per-run copy が使えたことの証拠であり、次回も copy 元になる persistent source が今も有効であることの証拠ではない。Codex CLI が実行中に refresh token を rotate して copy 側にだけ書く場合、成功直後の次 run が `refresh_token_reused` になり得る。この非対称性が本設計の判定規則を決める。

## Goals / Non-Goals

**Goals:**

- token 失効中に `/ops/llm-auth` が `logged_in` を返さない。issue に記録された実際の stderr（`refresh_token_reused` / `token_expired` / `Failed to refresh token`）を含む run が現 credential 世代にあれば降格する。
- 正常時は `logged_in` を返す。失敗 evidence が無い状態（run 履歴なしを含む）は `logged_in` とする。
- 再ログインで降格が解除される。WebUI login と `docker exec` fallback login の両方で解除される。
- 判定できない場合に「正常」と報告しない。
- 既存の raw output 保持 policy（issue #291 / #295）を変えない。

**Non-Goals:**

- token の自動再ログイン・自動リフレッシュ。
- 外部 API や CLI への実 probe。LLM 起動回数上限 policy に触れる経路を作らない。
- Claude 側の credential lifecycle 文言の網羅。Claude は既存の `authFailureSuspected` だけを evidence とする。
- 通知・alert 機構の新設。
- `/ops/monitoring` の provider outcome 集計の変更。
- readiness / SafetyFloor / order lifecycle への影響。

## Decisions

### D1: 監視 API は観測済み evidence を使い、実 probe を行わない

issue の候補1（失効 evidence の反映）を採る。候補2（実 probe）は、Codex CLI に副作用のない軽量な認証検証 subcommand が存在せず、`codex login --device-auth` は新しい device flow を開始してしまう。`codex exec` 相当を叩けば LLM 起動回数 policy と衝突する。監視 endpoint が polling される（WebUI System は 30 秒間隔で refetch する）ことを踏まえると、実 probe は policy 上も負荷上も採用できない。

**代替案**: `auth.json` の JWT payload を読んで exp を検証する。却下する。credential file の中身を読むことは secret 境界を監視 layer へ広げる。また Codex の auth.json 形式は CLI の内部実装であり pin していないため、形式変更で silent に壊れる。

### D2: evidence 源は既存の `authFailureSuspected` とする

issue #306 が `authFailureSuspected` を `primary category == AUTHENTICATION || authEvidenceObserved` へ拡張し、本 issue が根拠とする障害の文言を evidence 集合へ追加した。監視 API はこの既存 signal をそのまま evidence 源として使う。

**新しい signal を追加しない。** 同じ文言を2箇所で追跡すると、CLI の log 文言が変わったときに片方だけ更新される。`authFailureSuspected` は「認証失敗を疑う」ことを表す運用 signal として既に定義されており、監視 status が表す意味と一致する。

**代替案**: credential lifecycle 専用の signal を別に持つ。却下する。#306 以前は「文言を `CODEX_KNOWN_AUTH_EVIDENCE_TEXTS` へ足すと raw output 保持 policy が変わる」ことが分離の理由だったが、#306 がその変更を意図的な trade-off として既に受け入れたため、分離する理由が残っていない。

### D3: evidence は in-process の live state として保持し、DB を検索しない

失敗 evidence を audit log から検索する方式は採らない。監視 endpoint が append-only な `command_event_log` を polling して「evidence が無いこと」を確かめようとすると、次の難しさが同時に発生する。

- `RUNNER_PHASE_COMPLETED` は LLM phase 専用ではなく deterministic phase も出すため、行の解釈と malformed 判定が絡み合う
- 1 decision run が複数行を出すため、row bound の意味が provider 別・世代別の分類より先に効いてしまう
- `payload` は TEXT 列であり、JSON 述語は索引化できない。log が育つほど cast と走査のコストが増え、正常な token でも statement timeout で `unknown` に落ちる
- 上記を index で救おうとすると、育った table への index provisioning という別の運用リスクを production 起動経路へ持ち込む

これらはすべて「過去の記録を検索して現在の状態を再構成する」ことに由来する。しかし必要なのは過去の全履歴ではなく、**現在の credential が失敗を出したかどうか**という 1 bit である。invocation は同一プロセス内で走るため、観測した時点でその 1 bit を持てばよい。

そこで、daemon tick の liveness を `MutableLlmDaemonTickStatus` が in-process に保持しているのと同じ形で、CLI auth の失敗 evidence を in-process の live state として保持する。

- `LlmAuthEvidenceState` が、provider ごとに「最後に観測した失敗 evidence」を保持する。保持する値は provider、観測時刻、その run が使った credential source の mtime の3つだけで、provider output も例外 message も持たない。
- `LlmInvocationAuditor` が失敗 evidence（`authFailureSuspected` または credential lifecycle signal）を観測したら state を更新する。
- `/ops/llm-auth` はこの state を読むだけで判定する。DB read は行わない。

#### D3b: 更新は DB append より前に、provider 単位で原子的に行う

**順序**: state の更新は `commandEventLog.append()` より前に行う。auditor の既存実装は signal 算出後に audit を append し、append の失敗を後段で throw する。append 後に state を更新すると、DB 障害中に認証失敗を観測しても state が更新されず、`/ops/llm-auth` が `logged_in` を返す。DB に依存しないことがこの方式の要点なので、観測した時点で先に記録する。

**原子性**: provider ごとの evidence は `ConcurrentHashMap` の `compute` で更新し、read-modify-write の lost update を防ぐ。`MutableLlmDaemonTickStatus` は単一 scalar なので `@Volatile` で足りるが、provider map の複合更新はその前例に当たらない。

**上書き規則**: 新しい evidence が既存を無条件に上書きするのではなく、**credential 世代が古い evidence では上書きしない**。旧世代の invocation が新世代の invocation より後に完了し得るため、無条件上書きだと旧世代 evidence が新世代 evidence を消し、marker 比較で無視されて `logged_in` に戻る。世代が同じ場合は観測時刻の新しい方を残す。

DB の audit 記録は従来どおり残る（D2 の payload field を含む）。人間が事後に診断するための記録としては audit log が正本であり、監視 status の live 判定は in-process state が担う、という役割分担にする。

**代替案**: audit log を検索する（前案）。却下する。上記4つの難しさが本質的に付いてくるうえ、いずれの緩和策も「監視 endpoint の polling に耐える DB 検索基盤」を新設する方向へ向かい、hobby プロジェクトの scope を超える。

#### この方式の検知範囲（ユーザー確認済み）

検知の対象は **Ktor process 内で実行された invocation が観測した失敗** に限る。これは意図的な縮退であり、次の2つが検知範囲の外にあることをユーザー確認のうえ受容する。

**限界1: 別プロセスの direct runner は対象外。** `OneShotRunnerMain` は `trading` module の独立した `JavaExec` エントリで、独自に `TradingRuntime` と invoker を構築する。`docs/llm-obsidian-production-setup.md` は これを「scheduler と同時実行せず、必要な場合だけ隔離して実行する」maintenance 経路として残している。この経路の失敗は in-process state に届かないため、監視 API は降格しない。operator が手元で結果を見ながら実行する隔離操作であり、監視 API が拾えなくても実害が小さいと判断した。spec で明示的に除外する。

**限界2: process 再起動で state が消える。** 再起動直後は evidence 無しとして `logged_in` を返し、次の in-process invocation が失敗するまで戻らない。`daemon.enabled` と `llm.launchEnabled` の code default はいずれも false であり、これらが無効な構成では invocation が長期間発生せず、失効が継続していても `logged_in` が続き得る。この限界も受容する。invocation が起きない状態では、そもそも token 失効による実害（proposer run の失敗）も発生していない。

この2つを埋めるには DB を正本にする必要があり、その方式は上記4つの難しさを全部連れてくる。検知範囲を縮退させるほうが、本 issue の主目的（daemon 経由の継続的な失敗を監視 API が `logged_in` と報告し続ける状態を無くす）に対して費用対効果が高いと判断した。

### D3a: 世代の判定は credential source の mtime で行う

再ログインすると Codex CLI は `auth.json` を書き直すため mtime が更新される。evidence が持つ「その run が使った credential source の mtime」が現在の marker mtime より古ければ、その evidence は解消済みの世代のものとして無視する。これにより WebUI login と `docker exec --user 10001 codex login --device-auth`（`llm-cli-invocation-contract` の Requirement「Production Codex fallback login updates the persistent auth source as appuser」で規定）のどちらでも降格が解除される。同 requirement は auth.json の mtime 更新を persistence の検査手段として既に定めており、本設計はその観測点を再利用する。

mtime は `FileTime` が返す精度のまま保持し、millis へ丸めない。比較は「evidence の source mtime が現在の marker mtime より**厳密に古い**なら旧世代」とする。等しい場合は現世代として扱い、降格を維持する。同一時刻の衝突は、失効を見逃す側ではなく降格を残す側に倒す。

renderer は copy の直前に source の mtime を読む。copy と mtime 取得は原子的に結べないため、両者の間に再ログインが割り込むと、実際に copy された内容と記録した世代がずれ得る。この race は次のように扱う。

- copy より **前** に mtime を読む。記録される世代は「copy された内容と同じか、それより古い」ことが保証される。
- 世代が実際より古く記録された場合、その evidence は marker mtime より古いと判定されて無視される。つまり誤りは「失効を見逃す」方向に倒れる。
- 見逃した場合も、次の invocation が新しい世代で evidence を作り直す。降格は遅れるが誤検知はしない。

逆向き（copy 後に読む）にすると、旧 credential で失敗した run が新世代の evidence として記録され、再ログイン済みなのに降格が残る。解除経路が再ログインしかない設計（D4）では、この誤りは operator が解消できない状態を作るため避ける。

### D4: 判定は「現世代の失敗 evidence が存在するか」とし、成功では解除しない

現世代の失敗 evidence があれば `TOKEN_SUSPECT`、無ければ `LOGGED_IN`。

**「成功した invocation が降格を解除する」規則は採らない。** 理由は Context に書いた credential copy の非対称性にある。invocation の成功が証明するのは per-run copy が使えたことであり、persistent source が今も有効であることではない。source への write-back が無い以上、成功を「credential が回復した証拠」として採用する根拠がない。加えて、失敗 evidence を持たない失敗（network failure、timeout、cleanup failure など）は「認証成功」を意味しないため、それらが降格を解除してはならない。

降格の解除経路は再ログイン（marker mtime の更新）だけになる。これは operator にとって明快で、`llm-cli-invocation-contract` が既に定める復旧手順と一致する。副作用として、一過性の認証失敗が自然回復しても `token_suspect` が残る。これは受容する。`/ops/llm-auth` は診断用であり readiness にも admission にも参加しないため、残った `token_suspect` が取引を止めることはなく、operator は再ログインで明示的に解消できる。

state の更新は失敗 evidence の記録だけとし、成功時に消さない。並行・順序の扱いは D3b の上書き規則に従う。

### D5: 判定不能は `UNKNOWN`

marker の mtime を読めない場合（`IOException` など）は `UNKNOWN` を返す。`LOGGED_IN` を維持しない。判定不能を「正常」と報告することが本 issue の原因そのものであるため、同じ誤りを新経路で作らない。

evidence state 自体は in-process の変数読み取りであり、失敗する経路も blocking する経路も持たない。したがって DB 由来の失敗分類、bound 到達、malformed payload、cancellation の扱いはいずれも不要になる。`snapshot()` は suspend のまま、DB read を伴わない。

### D6: evidence state は Ktor 内の全 invocation 経路へ配線する

`LlmInvocationAuditor` は Ktor process 内で4箇所から独立に構築されている。

| 経路 | 構築箇所 |
|---|---|
| decision run の one-shot | `OneShotLlmRunner` の内部 |
| daemon pre-filter | `LlmDaemonSchedulerWorker` |
| reflection runner | `ReflectionRunnerWorker` |
| evaluation | `Application.kt` |

reflection は provider として Codex を選択し得るため、one-shot だけに配線すると reflection 経由の失効が state に届かない。evidence state は `Application.kt` の runtime resource として1 instance だけ作り、上記4経路すべての factory / constructor へ明示的に渡す。経路ごとに composition test で配線を確認する。

`DefaultLlmAuthService` の evidence state も同じ instance を受け取る。nullable な constructor 引数とし、null なら marker 存在だけで `LOGGED_IN` を返す。既存 test と、auth service だけを組む構成を壊さないため。

### D7: PR を2段の stacked PR に分ける

- **PR 1（`:trading`）**: credential 世代（`authSourceObservedAt`）の観測と運搬、`LlmAuthEvidenceState` の定義、auditor からの記録。監視側の挙動は変えない。
- **PR 2（`:fukurou`、base は PR 1）**: `TOKEN_SUSPECT` status、`DefaultLlmAuthService` の判定、`Application.kt` の wiring、route の `.describe {}`、docs。

PR 2 は PR 1 が定義する state に依存するため、この順序でなければ PR 2 単独では evidence を作れない。PR 1 は単独で merge しても、payload に診断 key が1つ増え in-process state が誰にも読まれないだけで、既存 consumer に影響しない。

新しい field は既存 data class の default 付き引数として足すため、cross-module の test fixture を壊さない。

## Risks / Trade-offs

- **[既知文言の照合は不完全]** → `authFailureSuspected` は既知文言との一致と primary category 解決に依存するため、CLI の log 文言が変われば検知が silent に落ちる。これは #306 が既に受け入れている限界であり、本 change はそれを引き継ぐ。完全性の主張ではなく既知の失敗形に対する検知である旨を spec に明記する。
- **[降格は再ログインでしか解除されない]** → 一過性の認証失敗が自然回復しても `token_suspect` が残り、operator が不要な再ログインをする。D4 の意図的な trade-off として受容する。逆方向（失効中に `logged_in`）が本 issue の原因であり、そちらを確実に潰すことを優先する。
- **[本文への言及による false-positive]** → invocation の出力本文が既知の認証文言に言及すると `authFailureSuspected` が立ち、降格する。#306 が受け入れた限界を引き継ぐ。結果は operator の確認作業であり、取引には影響しない。
- **[marker mtime が更新されない再ログイン経路があると降格が解除されない]** → 誤って `token_suspect` のまま残る。緩和: root 実行 login が appuser の auth source を更新しない問題は `llm-cli-invocation-contract` で既に「使ってはならない手順」として規定済み。正規手順は auth.json を更新する。運用 doc に「再ログイン後も token_suspect が残る場合は auth.json の mtime を確認する」を追記する。
- **[process 再起動で evidence state が消える]** → 再起動直後は失効中でも `logged_in` を返し、次の in-process invocation まで戻らない。`daemon.enabled` / `llm.launchEnabled` が false の構成では長期間続き得る。D3 の検知範囲としてユーザー確認済みで受容する。
- **[別プロセスの direct runner が対象外]** → `OneShotRunnerMain` で観測した失効は監視 API に届かない。D3 の検知範囲としてユーザー確認済みで受容し、spec で明示的に除外する。
- **[再ログインが同一 mtime 内に収まると解除できない]** → filesystem の timestamp 分解能内で再ログインが完了し marker の mtime が失敗時と同値のままだと、equality を現世代として扱う規則により `token_suspect` が解除されない。解除手段が再ログインだけなので、operator は mtime が変わるまで待って再度 login する必要がある。runbook に明記する。同値を旧世代側に倒すと失効を見逃すため、この向きを選ぶ。
- **[Claude の credential file 選択が renderer と auth service で一致しない]** → renderer は候補のうち最初の regular file を copy し、auth service は最初の非空 regular file を marker とする。先頭候補が空 file の異常時にだけ、両者が別 file を指し Claude の世代比較が誤る。issue の「やらないこと」に沿って Claude 側の深追いはせず、既知の限界として PR に記録する。Codex は候補が1つのため影響しない。
- **[copy と mtime 取得の race]** → 両者の間に再ログインが割り込むと世代が実際より古く記録され得る。D3a のとおり誤りは「失効を見逃す」方向に倒し、次の invocation が作り直す。
- **[運用上の詰み]** → 上記の false-positive や一過性の失敗で `token_suspect` になった場合、成功 run では解除できず、operator が device login を完了できない状況では表示が固定される。取引・readiness には影響しないため受容し、runbook に「再ログイン以外の解除経路はない」と明記する。別の解除機構は scope 拡大のため本 change では追加しない。
- **[status 値の追加が consumer を壊す]** → WebUI は status 文字列をそのまま表示し `logged_in` との完全一致で件数を数えるため、`token_suspect` は「logged in ではない」として自動的に正しく扱われる。緩和: WebUI の変更は不要だが、表示文言の確認を tasks に含める。
- **[Codex 限定の検知精度]** → Claude は `authEvidenceObserved` の対象外であり、`authFailureSuspected` は primary category が `AUTHENTICATION` の場合しか立たない。Claude の refresh token 失効は同じ精度で検知できない。issue の「やらないこと」に沿って先送りする。

## Migration Plan

DB schema 変更なし。index 追加なし。DB query 追加なし。`command_event_log` の payload に診断用の key が2つ増えるだけで、既存行の読み取りは影響を受けない（欠落 key は「signal なし」「世代不明」として扱う）。監視 status の判定は in-process state だけを読むため、起動経路にも DB 負荷にも新しい失敗様式を持ち込まない。rollback は revert のみで足りる。

## Open Questions

なし。D4 で「成功 run が credential 有効性を保証するか」という未検証前提そのものを設計から外したため、CLI の refresh 挙動を確定させる必要がなくなった。
