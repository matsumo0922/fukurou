## 1. Runtime policy

- [ ] 1.1 version 付き Falsifier policy enum を typed config に追加する
- [ ] 1.2 runtime catalog / candidate validation / config tests を更新する

## 2. Durable policy decision

- [ ] 2.1 policy decision domain model と repository contract を追加する
- [ ] 2.2 PostgreSQL table / bootstrap と Exposed repository の atomic idempotent save / read を追加する
- [ ] 2.3 in-memory repository に同じ contract を追加する

## 3. Regression evidence

- [ ] 3.1 same-payload retry / different-payload conflict / missing-side conflict を unit / integration test する
- [ ] 3.2 unknown runtime policy rejection と default behavior 不変を test する

## 4. Documentation and validation

- [ ] 4.1 config / persistence docs に foundation と activation 禁止を現在形で追記する
- [ ] 4.2 OpenSpec validation、targeted test、full test / detekt / build を実行する
