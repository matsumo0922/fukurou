## Why

Issue #192 の production 障害注入は、対象状態の待機、他 deploy の停止、2 回の owner gate、検証後の撤去まで必要で、後続開発を止めるコストに対して得られる追加証拠が小さい。
（ユーザー確認済み）single-owner hobby system の実験スループットを優先して意図的な注入を中止し、PR #270 で追加した default-off の一時 seam を残さず撤去する。

## What Changes

- （ユーザー確認済み）Issue #192 の WebSocket 切断と open-position restart の production 注入を実施しない。Issue の元 DoD は達成済みと扱わず、cleanup 後に not planned として閉じる。
- （ユーザー確認済み）temporary controller、hidden route、compose flag、application/worker wiring、WebSocket abort 境界、fixed-PK reader、注入専用 tests と temporary deploy documentation を削除する。
- （agent 仮決め・反証反映）未知の既存 audit row があっても activity feed を壊さないよう、requested/executed `CommandEventType` と activity catalog 表示だけを historical decoding vocabulary として残す。writer、fixed-PK reader、runtime route は削除し、DB row の削除や backfill は行わない。
- 通常の WebSocket 接続、自然切断、`PROCESS_RESTART` gap recovery、paper receipt/execution、evaluation exclusion は変更しない。

## Capabilities

### New Capabilities

- `issue-192-fault-seam-cleanup`: 中止した Issue #192 の一時 fault-injection surface が runtime、設定、表示、現在形ドキュメントに残らないことを定義する。

### Modified Capabilities

- なし。

## Impact

- `fukurou` の application routing/controller/worker wiring と専用 tests。
- `trading` の injected disconnect interface、GMO WebSocket session mutation、command-event primary-key reader と専用 tests。historical command-event decoding vocabulary は維持する。
- `docker-compose.prod.yml`、`docs/deploy.md`、activity catalog/i18n/golden fixture。
- DB schema、runtime config、paper ledger、既存 gap/exclusion/audit row、通常 deploy contract は変更しない。
