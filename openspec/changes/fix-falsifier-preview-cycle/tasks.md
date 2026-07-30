## 1. Falsifier tool contract

- [ ] 1.1 Falsifier canonical allowlist から `preview_order` を除外し、preview schema registration は維持する
- [ ] 1.2 Falsifier prompt と production-equivalent CLI canary を read-only verdict flow に同期する

## 2. Regression evidence

- [ ] 2.1 production OneShot runner が構築する Falsifier manifest に `preview_order` が含まれない回帰テストを追加する
- [ ] 2.2 APPROVED 後の runner preview → place ordering と preview rejection の既存 production call path test を確認する

## 3. Documentation and validation

- [ ] 3.1 `docs/` と system prompt の Falsifier / deterministic preview 現在形記述を同期する
- [ ] 3.2 OpenSpec validation、targeted test、full test / detekt / build、CLI canary self-test を実行して記録する
