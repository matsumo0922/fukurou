## 1. Internal boundary

- [ ] 1.1 public `PlaceOrderCommand`、preview/place `Broker` signature、MCP wire schemaを変更せず、broker package内にinternal `AuthorizedPreviewOrder` / `AuthorizedPlaceOrder` envelopeとboundaryを追加する
- [ ] 1.2 policy decision repositoryをinternal boundaryへ配線し、permitとdurable decision/eventの全identityをpre-lookupで検証する
- [ ] 1.3 partial/mismatchをtyped authority-indeterminate、repository failureをtyped authority-unavailableとして返し、NO_TRADEを意味させない
- [ ] 1.4 production runnerをinternal boundaryへ接続せず、OFFを含む全entryのFalsifier behaviorとstatus/outcome mappingを維持する

## 2. Fingerprint and namespace

- [ ] 2.1 `schemaVersion=falsifier-authority-v1`とfield insertion orderを固定したcanonical JSONへcommand/permitを符号化し、UTF-8 bytesのSHA-256で`runner-place-v2-<hash>`を導出する
- [ ] 2.2 nullableを`JsonNull`、stringをescaped `JsonPrimitive`、BigDecimalをnormalized `toPlainString`の`JsonPrimitive`として符号化する
- [ ] 2.3 authorized placeをauthority検証、fingerprint検証、authorized replay readerの順に固定する
- [ ] 2.4 public preview/placeで未使用・既存のv2 IDをresult lookup、preview、mutationより前に拒否する

## 3. Replay-only behavior

- [ ] 3.1 public lookup semanticsを変更せず、authorized replay専用internal reader/capabilityと`Exact` / `Missing` / `Ambiguous`結果を追加する
- [ ] 3.2 BUY entry candidate厳密1件、intent一致、non-null trade group、全related rows同一groupの場合だけExactを返し、同一groupのprotective SELLを許可する
- [ ] 3.3 BUY 0件をMissing、BUY複数、intent/group不一致、別group rowをAmbiguousとしてtyped fail-closedへ変換する
- [ ] 3.4 authority/fingerprint検証後のExactをintent consumed判定より優先し、Missingはtyped `authorized new mutation unsupported`でledger/intentを変更しない
- [ ] 3.5 unsupported reader、reader failure、Ambiguousをpublic lookupへfallbackせずtyped indeterminate/fail-closedとして返す

## 4. Tests and documentation

- [ ] 4.1 public API/MCP schema不変とproduction runnerがOFFでもFalsifierを起動する回帰testを追加する
- [ ] 4.2 durable authority全identityの一致、各field mismatch、partial state、repository unavailableをtestする
- [ ] 4.3 canonical fingerprintの各business/authority field束縛、nullと文字列`"null"`、改行/quote/backslash/JSON metacharacter、encoding順序をtestする
- [ ] 4.4 public/MCP pathの未使用・既存v2 ID spoofがpre-lookupで拒否されることをtestする
- [ ] 4.5 seeded exact replayがconsumed intentより優先され、resultなしはunconsumed/consumed双方でfail closedになることをtestする
- [ ] 4.6 replay readerのBUY複数、protective SELL同居、wrong intent、null/wrong/別trade group、unsupported capabilityをtestする
- [ ] 4.7 `docs/mcp-runtime.md`をinactive authority/replay boundary、新規mutation未対応、production behavior不変、activation禁止を示す現在形へ更新する
- [ ] 4.8 関連docs grep、OpenSpec strict validation、関連test、admission isolation regression、detekt、buildをexact HEADで実行する

## Deferred

A2 `add-falsifier-policy-atomic-entry`でatomic flat predicateとin-memory/PostgreSQL authorized new mutation/concurrencyを実装する。
後続Bでruntime activation precondition、runner permit propagation/Falsifier skip、durable outcome-unknown mappingを実装する。
