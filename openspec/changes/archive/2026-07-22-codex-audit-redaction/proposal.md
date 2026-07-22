## Why

Issue #291: `LlmInvocationAuditor` は Codex provider の stdout/stderr を一律 `rawOutputOmitted=true` として監査 payload から破棄している。secret 混入を恐れた出力隠蔽が診断可能性を殺しており、single-owner 構成ではコストが利益を大きく上回る。Claude provider は既に `redactor.redactAndTruncate()` で secret masking + truncation して記録しており、Codex にも同じパターンを、安全に適用できる範囲で適用する。

5回の独立反証（falsify）を経て、当初想定していた設計はいずれも blocking な反例が見つかり撤回・修正した。

1. 「成功時も含めて Claude と対称に記録する」→ 既存のプライバシー非公開保証テスト（`OpsRouteTest`）との衝突が判明し撤回
2. 「`AUTHENTICATION` だけ除外すれば安全」→ Codex parser の先勝ち方式カテゴリ決定により、認証 evidence が `OUTPUT_CONTRACT`/`RATE_OR_SESSION_LIMIT`/`QUOTA_EXHAUSTED` に埋もれて漏洩することが実証され、この4カテゴリすべてを除外対象に拡張
3. 「`UNKNOWN_PROVIDER_FAILURE` も lifecycle カテゴリとして許可する」→ このカテゴリも実は Codex の出力テキストを解釈した結果の parser 経由カテゴリであり、同じ先勝ち方式の反例が成立することが判明し除外に追加
4. 「primary category が lifecycle 3カテゴリなら安全」→ `primaryProviderFailure()` が adapter 由来の `UNKNOWN_PROVIDER_FAILURE` より lifecycle category を優先するため、adapter が実は何かを検出していた複合ケースが漏れることが判明し、`invocationResult.providerFailure == null` を追加条件にした
5. 「adapter failure が無ければ安全」→ `DefaultLlmOutputParser` の stderr 認証判定が `terminalCount == 0` のときしか働かないため、成功 terminal + stderr の既知認証文言という組み合わせが漏れることが判明し、stderr の既知認証文言非含有チェックを追加した

さらに実装後のコードレビューで、本 PR が実際に診断可能にする範囲が想定よりさらに狭いことが判明した。`isSafeCodexLifecycleFailure()` が成立するのは「会話 turn 自体は完全に成功したが、その後の process 終了処理（exit/timeout/cleanup）が失敗した」場合のみであり、「launcher が Codex を起動できず、有効な turn を一度も生成できない」ような失敗は、有効な JSONL を出力できないため `schemaDrift` 経由で `OUTPUT_CONTRACT` に分類され、本 PR 後も診断できない（design.md の該当節を参照）。

最終的なスコープは「`processResult.status`/`exitCode`/cleanup 例外という process lifecycle 事実（`PROCESS_EXIT`/`PROCESS_TIMEOUT`/`CLEANUP`）・adapter failure なし・stderr に既知認証文言なし、の3条件がすべて揃ったときだけ、redactor 経由で stdout/stderr を記録する」という、狭いが構造的に安全な範囲に収束した。

**重要な限界**: issue #291 の直接の動機だった #282 障害の実際の production category は `OUTPUT_CONTRACT`/`SCHEMA_DRIFT`（issue #282 本文・owner コメントで確認済み）であり、上記の安全なアローリストには含まれない。つまり**本 PR は #282 と全く同じ障害が再発しても、その stderr は依然として確認できない**。`OUTPUT_CONTRACT` を安全に公開するには parser 側の追加設計（primary category と独立した認証 evidence 追跡）が必要で、本 PR のスコープを超えるため別 issue に切り出す（design.md の Follow-up 参照）。本 PR が実際に安全に改善するのは、上記13行目の訂正のとおり「turn 完走後の終了処理失敗」という、さらに狭いクラスの診断可能性ギャップである。

## What Changes

- Codex が **process lifecycle カテゴリ**（`PROCESS_EXIT` / `PROCESS_TIMEOUT` / `CLEANUP`）で失敗し、**かつ** adapter failure が存在せず（`invocationResult.providerFailure == null`）、**かつ** stderr に既知の認証失敗文言（`CODEX_STDERR_AUTH_FAILURES`）が含まれないときだけ、`redactor.redactAndTruncate(stdout)` / `redactor.redactAndTruncate(stderr)` を監査 payload に記録する（現状: 全カテゴリで `rawOutputOmitted=true`）
- Codex が **`AUTHENTICATION` / `RATE_OR_SESSION_LIMIT` / `QUOTA_EXHAUSTED` / `OUTPUT_CONTRACT` / `UNKNOWN_PROVIDER_FAILURE`**（Codex の出力テキストを解釈して分類されるカテゴリ）で失敗したとき、既存どおり raw output・token・path・prompt を一切残さない
- Codex の**成功時の挙動は変更しない**（既存どおり `stdout`/`stderr` を記録しない）
- Claude の挙動は変更しない（成功時は既存通り redactor 記録、失敗時は既存通り省略のまま）
- `rawOutputOmitted` フィールドと関連ロジックをコードベースから撤去する（記録しない場合は `stdout`/`stderr` キー自体を出さない）
- `SecretRedactor` の masking パターンが Codex 出力に対して十分か確認し、不足があれば同じ PR でパターンを追加する。ただし redactor は既知値の完全一致置換のままとし、token rotation 後の値が伏字にならない残存リスクは受容する（出力解析型への作り替えは行わない）
- `OUTPUT_CONTRACT` を安全に公開するための follow-up issue を別途起票する

## Capabilities

### New Capabilities

（なし）

### Modified Capabilities

- `llm-cli-invocation-contract`: 「Provider failures have stable typed categories」の Requirement を修正する。Codex の raw output 記録を process lifecycle カテゴリ（`PROCESS_EXIT`/`PROCESS_TIMEOUT`/`CLEANUP`）だけに限定するアローリストとして明文化し、`AUTHENTICATION`/`RATE_OR_SESSION_LIMIT`/`QUOTA_EXHAUSTED`/`OUTPUT_CONTRACT`/`UNKNOWN_PROVIDER_FAILURE` は既存どおり raw output を一切残さないことを明記する。「Pinned CLI output is parsed as a versioned contract」は成功時の挙動が変わらないため変更不要

## Impact

- `trading/src/main/kotlin/me/matsumo/fukurou/trading/runner/LlmInvocationAuditor.kt`: `phaseDetails()` 内の provider 分岐（L340-380 付近）
- `trading/src/main/kotlin/me/matsumo/fukurou/trading/runner/SecretRedactor.kt`: masking パターンの確認、必要なら追加（出力解析型への作り替えはしない）
- `trading/src/test/kotlin/me/matsumo/fukurou/trading/runner/LlmInvocationAuditorTest.kt`: 既存の `rawOutputOmitted` assertion の更新、Codex の process lifecycle 系失敗時（複合失敗ケース含む）の redacted 出力確認テストの追加、secret masking 回帰テストの追加
- `trading/src/test/kotlin/me/matsumo/fukurou/trading/runner/OneShotLlmRunnerTest.kt`: L2362 付近の `rawOutputOmitted` assertion の更新
- `docs/design.md`: L1309 付近の Codex raw output 非永続化の記述を現在の仕様に更新
- `fukurou/src/test/kotlin/me/matsumo/fukurou/OpsRouteTest.kt`: 変更不要（成功時の非公開保証テストはそのまま有効であることを実装時に確認する）
- 依存: なし（Epic #286 内の他 sub-issue と独立）
- 破壊的変更: なし（監査 payload に新しいキー `stdout`/`stderr` が Codex の process lifecycle 系失敗行にのみ出現するようになるが、既存キーの削除は `rawOutputOmitted` のみで、これは内部監査用フィールドでありコンシューマー契約ではない）
- 残存リスク: (1) `SecretRedactor` は既知値の完全一致置換であり、token rotation 後の新しい secret は伏字にならない (2) process lifecycle 系失敗でも、クラッシュ直前までの部分的な会話内容（取引戦略の断片）が stdout に残りうる (3) issue #291 の動機だった #282 と同形の `OUTPUT_CONTRACT`/`SCHEMA_DRIFT` 障害は、本 PR 後も診断できない。いずれも design.md で受容済み・別 issue 起票予定、PR の人間確認事項に転記する
