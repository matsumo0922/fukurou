## ADDED Requirements

### Requirement: No-trade audit payload retains allowlisted diagnostics without provider branching

Issue #320: no-trade exit の audit payload は、LLM provider によって診断情報の有無を変えない。system SHALL `NO_TRADE_EXIT` および HARD_HALT 拒否の audit payload へ、失敗原因の例外 message のうち fukurou 自身のコードが生成した定型 diagnostic に一致するものだけを記録する。system SHALL NOT provider が Codex であることを理由に message を省略し、provider 分岐による省略マーカーを出力する。system SHALL NOT allowlist に一致しない例外 message を記録する。

allowlist に一致しない cause については例外型名だけを残す。cause の型集合は閉じておらず（guard は `Throwable` を catch し、任意の cause を受ける）、既知値の完全一致置換による redaction では rotation 後の secret や変形された credential を伏字にできないため、任意 message の保存は secret 境界の外にある。

この requirement は raw provider output の記録可否を定めた「Provider failures have stable typed categories」とは別の面を扱う。no-trade payload が保持するのは fukurou 自身が生成した diagnostic 文字列であり、CLI process の stdout / stderr ではない。

#### Scenario: Codex run fails at the order placement boundary with a broker diagnostic

- **WHEN** Codex provider の decision run で `place_order` が queue snapshot の事前条件違反により失敗し、`place_order_failed` として no-trade audit が記録される
- **THEN** payload は broker が生成した `QUEUE_SNAPSHOT_UNAVAILABLE` 系 diagnostic を含み、どの事前条件で fail-closed したかを audit だけで特定できる

#### Scenario: Failure cause is an arbitrary provider or process exception

- **WHEN** no-trade audit へ渡された cause の message が allowlist のいずれにも一致しない
- **THEN** payload は例外型名だけを残し、message キーを出力しない

#### Scenario: Claude run fails at the same boundary

- **WHEN** Claude provider の decision run で同じ境界の失敗が起きる
- **THEN** payload の形は Codex の場合と同一であり、provider による分岐を持たない
