## 1. PR 1: credential 世代の観測と evidence state（`:trading`）

- [x] 1.1 `DefaultLlmCommandRenderer` が credential source を **copy する前に** 観測した source mtime を `RenderedLlmCommand` へ載せ、`LlmInvoker` が `LlmInvocationResult` へ伝搬する。値は `FileTime.toInstant()` の精度を保つ（millis へ丸めない）。新 field は default 付きとし、既存 fixture を壊さない
- [x] 1.2 `LlmAuthEvidenceState` を定義する。provider ごとに「最後に観測した失敗 evidence（観測時刻、credential 世代）」だけを保持し、provider output・例外 message・credential 内容は持たない。更新は `ConcurrentHashMap.compute` で provider 単位に原子的とし、既存 evidence より古い世代では上書きしない（同一世代なら観測時刻の新しい方を残す）
- [x] 1.3 `LlmInvocationAuditor` が `authFailureSuspected`（issue #306 で `AUTHENTICATION || authEvidenceObserved` へ拡張済み）を観測したとき、`commandEventLog.append()` より **前に** evidence state を更新する。state は nullable な依存とし、未注入なら更新しない
- [x] 1.4 `LlmInvocationAuditor.phaseDetails()` が、source mtime を観測できたときだけ `authSourceObservedAt`（ISO-8601）を payload へ出す（人間の事後診断用）
- [x] 1.5 `LlmAuthEvidenceStateTest` を追加する: 初期状態は null / provider ごとに保持 / 古い世代が新しい世代を上書きしない / 新しい世代で置き換わる / 同一世代なら観測時刻の新しい方を残す / 世代不明なら観測時刻で比べる
- [x] 1.6 `LlmInvocationAuditorTest` に test を追加する: `authFailureSuspected` 観測で state が更新される / audit append が失敗しても state は更新済みである / 成功時に state が更新されない / payload に `authSourceObservedAt` が出る / 観測できないとき key が出ない / state 未注入でも動作する
- [x] 1.7 renderer の credential copy 経路に test を追加する: Codex / Claude それぞれで copy 前に観測した source mtime が rendered command に載る / source が無い場合は載らない / millis 未満の精度が保たれる
- [x] 1.8 `make test` / `make detekt` を通し、PR 1 を作成する

## 2. PR 2: 監視 status の降格（`:fukurou`、base は PR 1）

- [x] 2.1 `LlmAuthStatus` に `TOKEN_SUSPECT("token_suspect")` を追加する
- [x] 2.2 `DefaultLlmAuthService` に evidence state を nullable な constructor 引数として受け取る。null なら現行動作（marker 存在だけで `LOGGED_IN`）を維持する
- [x] 2.3 `providerStatus()` を更新する。marker 検出時に marker の mtime を読み、evidence の世代が marker mtime より厳密に古ければ無視、同値以降なら `TOKEN_SUSPECT`、evidence が無ければ `LOGGED_IN` とする
- [x] 2.4 marker の mtime を読めない場合（`IOException` など）は `UNKNOWN` を返す
- [x] 2.5 status detail を固定の非 secret 文字列にする（evidence の種別を示すが、provider output・例外・credential 内容は含めない）
- [x] 2.6 `Application.kt` に evidence state を runtime resource として1 instance 作り、`DefaultLlmAuthService` と、Ktor 内の全 invocation 経路（decision-run one-shot、daemon pre-filter、reflection runner、evaluation）の auditor 構築箇所へ明示的に渡す
- [x] 2.7 `/ops/llm-auth` の `.describe {}` に `token_suspect` と `unknown` の意味を日本語で追記する
- [x] 2.8 `LlmAuthServiceTest` に spec の Scenario 対応 test を追加する: `authFailureSuspected` で降格 / 後続の成功が降格を解除しない / 後続の非認証 failure が降格を解除しない / evidence なしで `logged_in`（回帰）/ marker 更新で古い世代の evidence を無視 / 世代が marker mtime と同値なら降格を維持 / 他 provider の evidence で降格しない / marker 不在で `logged_out` / mtime を読めないとき `unknown` / evidence state 未注入で `logged_in` / state が空（再起動相当）で `logged_in`
- [x] 2.9 `OpsRouteTest` に production route 経由で `token_suspect` が wire に出る test と、status detail に secret 相当が含まれない test を追加する
- [x] 2.10 renderer → invoker → auditor → evidence state → auth service → route の配線が通ることを production wiring 経由の統合テストで確認する。加えて、4つの invocation 経路（one-shot / pre-filter / reflection / evaluation）それぞれが同一 state instance を受け取っていることを composition test で確認する
- [x] 2.11 `token_suspect` / `unknown` が `/health/ready` と scheduler admission に影響しないことを、依存グラフ上 CLI auth を参照していないことの確認として記録する（新規 test が不要ならその旨を記録する）
- [x] 2.12 `docs/llm-obsidian-production-setup.md` の CLI auth 判定の記述を現在形で更新する。`token_suspect` の意味、再ログインでのみ解除されること（false-positive でも他の解除経路が無いこと）、process 再起動で evidence が消えること、別プロセスの direct runner が検知対象外であること、解除されない場合に auth.json の mtime を確認し必要なら mtime が変わるまで待って再 login することを追記する
- [x] 2.13 WebUI System 画面が `token_suspect` を「logged in ではない」として扱い、そのまま表示することを確認する（変更が不要なら不要と記録する）
- [x] 2.14 `make test` / `make detekt` / web の test を通し、PR 2 を PR 1 の branch を base として作成する
