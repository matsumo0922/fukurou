## 1. Delivery split

- [ ] 1.1 実装前にproduction/testのhuman-authored diffを見積もる。1,000行超見込みならPR A1をinternal envelope・authority/fingerprint・public v2 guard・new mutation fail-closed、PR A2をin-memory/PostgreSQL atomic mutation・replay/concurrencyへstack分割する
- [ ] 1.2 PR A1単独ではauthorized new mutationをfail closedにし、production runner wiringとFalsifier behaviorを一切変更しない

## 2. Internal authority boundary

- [ ] 2.1 public `PlaceOrderCommand`、preview/place `Broker` signature、MCP wire schemaを変更せず、broker package内にinternal `AuthorizedPreviewOrder` / `AuthorizedPlaceOrder` envelopeとboundaryを追加する
- [ ] 2.2 permitとdurable decision/eventのdecision ID、intent ID、action、policy、required/reasons、runtime config version/hashを全identityで検証する
- [ ] 2.3 repository unavailable、partial、mismatchをresult lookup/mutation前のtyped authority-unavailable/indeterminate failureとして返す
- [ ] 2.4 production `OneShotLlmRunner`がinternal boundaryを呼ばず、全entryで既存Falsifier/public broker pathを使うことを維持する

## 3. Fingerprint and replay gate

- [ ] 3.1 normalized command business fieldsとpermit全identityからcanonical SHA-256 `runner-place-v2-<hash>`を導出する
- [ ] 3.2 authorized pathをauthority検証、fingerprint検証、existing result lookup、consumption/new mutationの順に固定する
- [ ] 3.3 exact existing resultをconsumed intentより優先し、existing resultなしのconsumed intentだけを拒否する
- [ ] 3.4 public/MCP place pathで未使用・既存の`runner-place-v2-` IDをresult lookup前に拒否する

## 4. Atomic backend capability

- [ ] 4.1 authorized market/resting mutation用のinternal ledger capabilityを追加し、未対応backendをread-check-write fallbackなしでfail closedにする
- [ ] 4.2 in-memory backendのstate write lock内でopen position 0件かつrisk-increasing open entry order 0件を検証してintent consumptionとwriteを行う
- [ ] 4.3 PostgreSQL backendの既存ledger mutation lock/transaction内で同じflat predicate、intent consumption、writeを行う
- [ ] 4.4 market entry、resting entry、market-event resting fillが同じbackend linearization boundaryで競合することを確認する

## 5. Tests and documentation

- [ ] 5.1 public command/Broker API/MCP schema不変と、production runnerがOFFでもFalsifierを起動する回帰testを追加する
- [ ] 5.2 authority全identityの一致、partial、mismatch、repository unavailableのinternal boundary testを追加する
- [ ] 5.3 未使用/既存v2 IDのpublic spoof、payload/authority mismatch、exact replay consumed優先、resultなしconsumed拒否をtestする
- [ ] 5.4 二つのresting OFF競合とresting fill対market entryをin-memory/PostgreSQL両backendでtestする
- [ ] 5.5 market/resting両方のposition/order flat predicateと未対応backend fail-closedをtestする
- [ ] 5.6 `docs/mcp-runtime.md`をinactive plumbing、Falsifier behavior不変、OFF/CONDITIONAL activation禁止、後続PR Bの責務を示す現在形へ更新する
- [ ] 5.7 関連docs grep、OpenSpec strict validation、関連test、admission isolation regression、detekt、buildをexact HEADで実行する

## Deferred

後続activation PR Bで、runtime enforcement flag default false、production active snapshot precondition、runner permit propagation、canonical OFF ENTERのFalsifier skip、exact retry recoveryを設計する。
`ToolCompletionAuditFailedException(executed=true)`とauthority unavailableはdurable `OUTCOME_UNKNOWN` status/terminal/eventへmapし、no-tradeを作らない。
