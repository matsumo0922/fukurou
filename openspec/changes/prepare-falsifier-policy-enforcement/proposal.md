## Why

Issue #207 の OFF 実験を安全に有効化する前に、foundation permit を public/MCP command へ露出せず、broker 内で durable authority、replay identity、atomic flat invariant を検証できる inactive plumbing が必要である。
runner の Falsifier skip と outcome mapping は後続 change へ分け、authority boundary 自体を先に独立検証する。

## What Changes

- public `PlaceOrderCommand`、preview signature、`Broker` interface、MCP wire schema を変更せず、broker package 内に authorized preview/place envelope と internal boundary を追加する
- foundation の `OFF_V1 / ENTER` permit と durable policy decision の全 identity を internal boundary で検証する
- normalized command と authority から canonical fingerprint を作り、`runner-place-v2-<sha256>` namespace を internal OFF path 専用に予約する
- authority/fingerprint を既存 result lookup より先に検証し、exact replay は intent consumption より優先する
- public/MCP path からの未使用・既存 v2 ID をいずれも result lookup 前に拒否する
- in-memory / PostgreSQL の mutation 境界内で、open position と risk-increasing open entry order が共に 0 件であることを検証する
- authority を確立できない failure を mutation なしの typed indeterminate failure として返す
- runner production path は internal boundary を呼ばず、全 entry で従来どおり Falsifier を必須とする
- runtime docs と inactive plumbing の回帰テストを更新する

## Capabilities

### New Capabilities

- `falsifier-policy-enforcement-plumbing`: public contract と production behavior を変えずに OFF authority と atomic execution boundary を準備する契約

### Modified Capabilities

なし。

## Impact

- broker package 内の authorized envelope / internal boundary
- `PaperBroker` の public v2 namespace guard と authorized path
- in-memory / PostgreSQL ledger の atomic entry mutation
- policy decision repository の broker wiring
- broker、ledger、MCP contract、PostgreSQL integration test
- `docs/mcp-runtime.md`

runner の permit propagation、Falsifier skip、activation、outcome status mapping は後続 change で扱う。
`CONDITIONAL_V1`、shadow/evaluation、live trading は対象外である。
