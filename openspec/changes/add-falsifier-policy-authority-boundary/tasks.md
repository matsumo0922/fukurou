## 1. Internal boundary

- [ ] 1.1 public `PlaceOrderCommand`、preview/place `Broker` signature、MCP wire schemaを変更せず、broker package内にinternal `AuthorizedPreviewOrder` / `AuthorizedPlaceOrder` envelopeとboundaryを追加する
- [ ] 1.2 policy decision repositoryをinternal boundaryへ配線し、permitとdurable decision/eventの全identityをpre-lookupで検証する
- [ ] 1.3 partial/mismatchをtyped authority-indeterminate、repository failureをtyped authority-unavailableとして返し、NO_TRADEを意味させない
- [ ] 1.4 production runnerをinternal boundaryへ接続せず、OFFを含む全entryのFalsifier behaviorとstatus/outcome mappingを維持する

## 2. Fingerprint and namespace

- [ ] 2.1 normalized command business fieldsとpermit全identityからcanonical SHA-256 `runner-place-v2-<hash>`を導出する
- [ ] 2.2 authorized placeをauthority検証、fingerprint検証、existing result lookupの順に固定する
- [ ] 2.3 public preview/placeで未使用・既存のv2 IDをresult lookup、preview、mutationより前に拒否する

## 3. Replay-only behavior

- [ ] 3.1 authority/fingerprint検証後のexact existing resultをintent consumed判定より優先して返す
- [ ] 3.2 lookup failure、非一意result、command/authority mismatchをexact replayとして返さない
- [ ] 3.3 exact resultがないauthorized placeをconsumed状態にかかわらずtyped `authorized new mutation unsupported`でfail closedにし、ledgerとintentを変更しない

## 4. Tests and documentation

- [ ] 4.1 public API/MCP schema不変とproduction runnerがOFFでもFalsifierを起動する回帰testを追加する
- [ ] 4.2 durable authority全identityの一致、各field mismatch、partial state、repository unavailableをtestする
- [ ] 4.3 canonical fingerprintの各business/authority field束縛とmismatchをtestする
- [ ] 4.4 public/MCP pathの未使用・既存v2 ID spoofがpre-lookupで拒否されることをtestする
- [ ] 4.5 seeded exact replayがconsumed intentより優先され、resultなしはunconsumed/consumed双方でfail closedになることをtestする
- [ ] 4.6 `docs/mcp-runtime.md`をinactive authority/replay boundary、新規mutation未対応、production behavior不変、activation禁止を示す現在形へ更新する
- [ ] 4.7 関連docs grep、OpenSpec strict validation、関連test、admission isolation regression、detekt、buildをexact HEADで実行する

## Deferred

A2 `add-falsifier-policy-atomic-entry`でatomic flat predicateとin-memory/PostgreSQL authorized new mutation/concurrencyを実装する。
後続Bでruntime activation precondition、runner permit propagation/Falsifier skip、durable outcome-unknown mappingを実装する。
