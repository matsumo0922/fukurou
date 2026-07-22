## Context

`LlmInvocationAuditor.phaseDetails()` は Codex provider の stdout/stderr を一律 `rawOutputOmitted=true` として監査 payload から破棄している。この挙動は 5日前に archive された OpenSpec change `2026-07-17-harden-llm-cli-lifecycle` が意図的に導入したもので、既存の `llm-cli-invocation-contract` spec には「Codex authentication fails → audit records AUTHENTICATION without persisting raw output, token, path, or prompt」という Requirement/Scenario が既にある。

issue #291 は、この隠蔽が #282 障害（MCP launcher の stderr 1行があれば即日特定できた原因究明に1週間かかった）のコストに見合わないと判断し、撤去を求めている。ただし既存コードを読むと、`rawOutputOmitted=true` になる分岐（`LlmInvocationAuditor.kt` L362-363）は provider 非依存で、`providerFailure`（`AUTHENTICATION`/`RATE_OR_SESSION_LIMIT`/`PROCESS_EXIT`/`PROCESS_TIMEOUT`/`CLEANUP`/`OUTPUT_CONTRACT`/`UNKNOWN_PROVIDER_FAILURE` のいずれか）が立てば Claude でも省略される。Codex 固有の欠陥は「成功時（`providerFailure == null`）ですら常に省略する」という L371-375 の一箇所のみ。

## Goals / Non-Goals

**Goals:**
- Codex の成功時・失敗時（`AUTHENTICATION` を除く）に redactor 経由の stdout/stderr を監査 payload に記録する
- `rawOutputOmitted` フィールドと関連ロジックを撤去する
- secret masking が Codex 出力に対して十分か確認する

**Non-Goals:**
- Claude の挙動変更（成功時・失敗時とも既存のまま）
- `AUTHENTICATION` カテゴリの raw output 非保持方針の変更（既存 spec を維持）
- 監査 payload の schema / テーブル構造の変更
- ログ基盤・監視・alert の新設
- LLM prompt/response 本文の記録方針の変更

## Decisions

### 1. provider 失敗時の分岐を provider ごとに割る（ユーザー確認済み）

`providerFailure != null` の分岐を、Codex は `AUTHENTICATION` 以外のカテゴリで redactor 経由記録、Claude は現状の省略のまま、という非対称な実装にする。

検討した代替案：
- (A) 採用案。Codex だけ failure 時も redactor 記録、Claude は現状維持。issue の受け入れ条件（「Codex 失敗時に redact 済み出力が SQL で見える」）と非目標（「Claude 経路の挙動変更は対象外」）を文字通り満たす。Claude/Codex で挙動が非対称になるが、issue の動機（#282 は Codex 固有の MCP launcher 障害）とも整合する
- (B) 却下。Claude も含め両 provider で failure 時に redactor 記録する。Claude の failure 時挙動（現状: 省略）が変わり、issue の非目標と厳密には矛盾するため、AskUserQuestion で確認した上でユーザーが (A) を選択

### 2. `AUTHENTICATION` カテゴリは Codex でも raw output を一切残さない（ユーザー確認済み、既存 spec 維持）

`AUTHENTICATION` は既存 spec Scenario（archive 済み `2026-07-17-harden-llm-cli-lifecycle` 由来）通り、raw output・token・path・prompt を一切残さない。認証失敗時の CLI 出力には token/credential が redactor の既知 secret 値マッチングをすり抜ける形で含まれるリスクが他カテゴリより高いため、既存の最も保守的な扱いを維持する。

### 3. `rawOutputOmitted` は完全撤去し、フィールド自体を出さない（agent 仮決め）

`AUTHENTICATION` の場合、代替の「省略マーカー」フィールドは追加しない。`stdout`/`stderr` キーが存在しないことそのものが「記録していない」ことを表す（既存テストの `assertFalse(details.containsKey("stdout"))` と同じ形）。新しいフィールド名を追加すると監査 payload の schema が増えるため、issue の非目標（schema 変更なし）に沿って何も出さない方針にする。

### 4. `SecretRedactor` の masking パターン確認（agent 仮決め、実装時に検証）

`SecretRedactor` は既に環境変数キー（`API_KEY`/`SECRET`/`TOKEN`/`PASSWORD`/`CREDENTIAL`）と `CODEX_HOME`/`CLAUDE_CONFIG_DIR` 両方の auth file から secret 実値を収集する provider 非依存の作りになっている。実装時に Codex 特有の出力形式（MCP launcher のエラーメッセージ、Codex CLI の stderr フォーマット）を確認し、既知パターンで拾えない secret 形状がないか確認する。不足があれば同じ PR でパターンを追加する。

## Risks / Trade-offs

- [Risk] Codex の redactor が既知 env var / auth file 以外の場所に secret を持つ CLI ツール（例: 一時的に発行される MCP launcher token）を見落とす → Mitigation: 実装時に Codex 実際のエラーメッセージ形式を確認し、`SENSITIVE_ENV_KEY_PATTERNS`/`SENSITIVE_JSON_KEY_PATTERNS` で拾えるか検証する。拾えない場合はパターンを追加する
- [Risk] Claude/Codex で failure 時の挙動が非対称になり、将来の読み手が意図を誤解する → Mitigation: delta spec の Scenario に非対称である理由（Codex 固有の診断可能性ギャップ）を明記する
- [Trade-off] `AUTHENTICATION` は今回も redact 済み出力すら見せないため、Codex の認証周りの障害は今回の修正後も #282 と同様に診断が難しいままになる可能性がある → 許容する（既存 spec の意図的な安全側判断を尊重し、今回のスコープ外とする）

## Open Questions

（なし。ユーザー確認済みの決定のみで実装に入る）
