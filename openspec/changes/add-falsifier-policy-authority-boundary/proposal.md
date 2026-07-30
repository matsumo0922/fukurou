## Why

Issue #207 の OFF 実験を安全に有効化するには、foundation permit を public/MCP contract へ露出せず、durable authority と replay identity を既存 result lookup より前に検証する private boundary が必要である。
backend の新規 mutation と runner activation は後続 PR へ分け、この変更では authority と exact replay の境界だけを独立検証する。

## What Changes

- public `PlaceOrderCommand`、preview/place `Broker` interface、MCP wire schema を変えず、broker package 内に authorized preview/place envelope と internal boundary を追加する
- foundation permit と durable policy decision/event の全 identity を internal boundary で検証する
- normalized command と authority から canonical `runner-place-v2-<sha256>` fingerprint を作る
- public/MCP path の未使用・既存 v2 ID を、既存 result lookup より前に拒否する
- authorized path は authority/fingerprint 検証後に exact existing result を lookup し、intent consumption より優先して replay する
- authority を確立できない場合は、result の有無を断定しない typed authority-unavailable/indeterminate failure を返す
- exact result がない authorized request は backend capability 未実装として必ず typed fail-closed にする
- production runner は internal boundary へ接続せず、Falsifier behavior、status、outcome mapping を変更しない
- runtime docs と authority/replay boundary の回帰テストを更新する

## Capabilities

### New Capabilities

- `falsifier-policy-authority-boundary`: public contract と production behavior を変えず、OFF authority と v2 exact replay を検証する private broker 契約

### Modified Capabilities

なし。

## Impact

- broker package 内の authorized envelope / internal boundary
- `PaperBroker` の public v2 namespace guard と authorized replay path
- policy decision repository の broker wiring
- broker、runner、MCP contract の unit/integration test
- `docs/mcp-runtime.md`

atomic flat predicate と backend new mutation は後続 A2 `add-falsifier-policy-atomic-entry` で扱う。
runtime activation、Falsifier skip、durable outcome mapping は後続 B、conditional/shadow/evaluation/live trading は対象外である。
