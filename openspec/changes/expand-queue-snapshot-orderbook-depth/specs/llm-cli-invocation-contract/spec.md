## ADDED Requirements

### Requirement: No-trade audit payload retains allowlisted diagnostics without provider branching

Issue #320: no-trade exit の audit payload は、LLM provider によって診断情報の有無を変えない。system SHALL `NO_TRADE_EXIT` および HARD_HALT 拒否の audit payload へ、失敗原因の例外 message のうち、コード内で定義済みの diagnostic 文字列と完全一致するものだけを記録する。system SHALL NOT provider が Codex であることを理由に message を省略し、provider 分岐による省略マーカーを出力する。system SHALL NOT allowlist に完全一致しない例外 message を記録する。

判定は prefix や部分一致ではなく完全一致とする。guard は任意の `Throwable` を cause として受けるため、外部由来の例外が allowlist の prefix を持つ message を構築できてしまうと、その後続に secret を含んだ文字列が永続化される。完全一致であれば、通過する値はコードが定義した定数そのものに限られ、可変部分が存在しない。

allowlist に一致しない cause については例外型名だけを残す。cause の型集合は閉じておらず、既知値の完全一致置換による redaction では rotation 後の secret や変形された credential を伏字にできないため、任意 message の保存は secret 境界の外にある。

この requirement は raw provider output の記録可否を定めた「Provider failures have stable typed categories」とは別の面を扱う。no-trade payload が保持するのは fukurou 自身が生成した diagnostic 文字列であり、CLI process の stdout / stderr ではない。

#### Scenario: Codex run fails at the order placement boundary with a broker diagnostic

- **WHEN** Codex provider の decision run で `place_order` が queue snapshot の事前条件違反により失敗し、`place_order_failed` として no-trade audit が記録される
- **THEN** payload は broker が生成した `QUEUE_SNAPSHOT_UNAVAILABLE` 系 diagnostic を含み、どの事前条件で fail-closed したかを audit だけで特定できる

#### Scenario: Failure cause is an arbitrary provider or process exception

- **WHEN** no-trade audit へ渡された cause の message が allowlist のいずれにも完全一致しない
- **THEN** payload は例外型名だけを残し、message キーを出力しない

#### Scenario: An external exception carries a message that starts with an allowlisted diagnostic

- **WHEN** fukurou 以外が生成した例外の message が、allowlist の diagnostic 文字列で始まり、その後続に追加の文字列を持つ
- **THEN** payload は完全一致しないものとして扱い、message キーを出力しない

#### Scenario: Claude run fails at the same boundary

- **WHEN** Claude provider の decision run で同じ境界の失敗が起きる
- **THEN** payload の形は Codex の場合と同一であり、provider による分岐を持たない
