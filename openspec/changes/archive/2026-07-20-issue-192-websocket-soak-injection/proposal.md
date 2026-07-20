## Why

現行 `PAPER_WS_V1` の production 約定実績がまだなく、通常運転だけでは WebSocket gap と process restart 後にも paper execution の因果、評価除外、current KPI の母集団が保たれるかを実証できない。Issue #192 の完了条件を、待ち時間や fill 件数ではなく、resting entry 中の切断と open position 中の再起動を各1回だけ行った future-only evidence と hard fail 0件で判定可能にする。

## What Changes

- （ユーザー確認済み）通常の paper trading soak を継続し、resting entry が存在する状態で WebSocket transport を1回だけ切断する。
- （ユーザー確認済み）open position が存在する状態で Ktor process を1回だけ再起動する。
- （ユーザー確認済み）各注入で source lineage、market-data gap、影響対象の evaluation exclusion、current KPI 非混入を production evidence で確認する。
- （ユーザー確認済み）retroactive fill、ambiguous outcome の silent 解決、評価除外対象の current KPI 混入を1件でも観測した場合は完了扱いにせず、soak を停止して別の修正 change へ切り出す。
- （ユーザー確認済み）repository の通常較正では分析・実験を小PRへ直行させるが、本件はユーザーの明示指示により change-local OpenSpec として設計する。1回限りの operator procedure は archive 時に main specs へ同期しない。
- （ユーザー確認済み・反証後の人間判断）host から WebSocket socket を推定せず、application が所有する active WebSocket session を expected session ID 付きで1回だけ abort する最小の一時的 ops seam を追加する。個人が単独で使う hobby system として、seam は default-false の deployment flag と既存 Cloudflare Access だけで保護し、専用 token、capability file、WebUI は追加しない。検証後に code、route、flag を削除して change を完了する。
- （agent 仮決め）DB schema、runtime config、dashboard は追加しない。process restart は同一 image の planned container restart とする。
- （agent 仮決め）実行 revision、account epoch、対象 entity、session、gap、receipt、execution、exclusion、KPI 判定、復旧結果を secret-free な `implementation-evidence.md` に固定し、未観測項目を成功へ読み替えない。

## Capabilities

### New Capabilities

- `paper-websocket-fidelity-verification`: 現行 WebSocket execution semantics の通常 soak、2つの限定障害注入、future-only evidence、hard fail と復旧判定を定義する。

### Modified Capabilities

- なし。

## Impact

- `openspec/changes/issue-192-websocket-soak-injection/` の delta spec、設計、task、production evidence。
- `GmoPublicWebSocketMarketEventStream` の active session registry、一時的な fault-injection controller/ops route、default-false deployment flag、append-only one-shot audit、対象 tests と運用 documentation。route は flag が false の通常 revisionでは登録せず、検証後に seam 全体を削除する。
- application 所有 session 1本の abort と、同一 `fukurou-ktor` container/image の planned restart。container network、PostgreSQL container/volume、runtime config、paper account はoperatorが変更しない。
- 注入に応答する application は `market_data_sessions` と `orders` を更新し、`market_data_gaps` と `evaluation_exclusions` を追記する。resting entry は取消され、影響 position/order/decision run は current evaluation 母集団から不可逆に除外される。`paper_market_event_receipts` と正当な `executions` は復旧後の realtime event に応じて追記され得る。
- これらの durable write は既存 paper-fidelity contract の意図した動作であり削除・backfillしないが、Epic #181 の評価母集団を縮小するため、各 production mutation 直前に影響 entity inventory を提示して owner の明示 go/no-go を得る。
- DB/API の evidence query 自体は read-only とし、raw payload・secret を保存しない。
- temporary ops route の存在期間だけ application code、production compose の flag、NAS `.env` が変わる。seam追加・撤去の2deployも`PROCESS_RESTART` gapと不可逆exclusionを生み得るため、各deployはflat stateを既定gateとし、満たせない場合は別owner承認を要する。個人運用ではverificationからcleanupまで他のdeployを行わず、最長72時間で条件が揃わなければcleanupを先行して後日再計画する。DB schema、Dockerfile、OpenAPI、WebUIの操作surface、LLM strategy、fill 件数・レジーム数の完了条件には変更なし。historical auditを表示するenumと既存catalog用のlabel/descriptionだけはcleanup後も残す。seam削除後の最終 revisionに fault-injection surfaceやtemporary flagを残さない。
