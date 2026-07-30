## Why

Issue #207 Phase 1: Falsifier が承認前に `preview_order` を呼べるため、fresh な Falsifier 承認がないことを理由に preview が拒否され、その拒否結果を根拠として Falsifier 自身が拒否する循環参照が発生している。deterministic preview は Falsifier 承認後に runner が実行する既存の production path に限定し、反証判断を承認前 preview から切り離す。

## What Changes

- （ユーザー確認済み）Falsifier の canonical tool policy から `preview_order` を除外し、承認前に deterministic preview を実行できないようにする。
- （agent 仮決め）Falsifier prompt では read tools による独立検証だけを求め、preview の利用を示唆しない。
- （ユーザー確認済み）Falsifier が APPROVED を保存した後に runner が `preview_order` と `place_order` を順に実行する既存経路は維持する。
- （ユーザー確認済み）Phase 2 の期間比較、policy version 記録、判定コメントは後続の独立 change / stacked PR に分離する。

### Issue #207 の受け入れ条件との対応

- この stage: Phase 1 の deterministic preview 循環修正と production call path の回帰テスト。
- 後続 stage: Phase 2 の Falsifier on/off、条件起動、期間・policy version 記録、descriptive comparison。
- non-goal: 8-arm ablation、blind scorer、provider/model/prompt/SafetyFloor の変更。

## Capabilities

### New Capabilities

- `falsifier-preview-protocol`: Falsifier の承認前 tool policy と、承認後に runner が deterministic preview を実行する順序を定義する。

### Modified Capabilities

なし。

## Impact

- `trading` module の Falsifier canonical MCP tool catalog、prompt、runner regression test。
- Falsifier の利用可能 tool contract と MCP manifest hash が変わる。
- DB schema、paper ledger、SafetyFloor、注文 lifecycle の承認後処理、public API は変更しない。
