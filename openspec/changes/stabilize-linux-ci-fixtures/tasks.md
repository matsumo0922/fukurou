## 1. Population bound fixture

- [x] 1.1 1件 scoped + 20,001件 scope外で正常 scoped aggregation を証明する
- [x] 1.2 同じ period の global population で entity limit 超過の typed failure を証明する
- [x] 1.3 targeted Postgres test の成功と実行時間を記録する

## 2. Linux process and gateway fixtures

- [x] 2.1 recovery child を supervisor completion contract と bounded PID observation に合わせる
- [x] 2.2 gateway-start fixture で production が選ぶ exact socket path を妨害する
- [x] 2.3 socket blocker を成功・失敗の両経路で cleanup する
- [x] 2.4 Linux container と macOS で担当二テストを targeted 検証する

## 3. Validation and documentation

- [x] 3.1 `make test`、`make detekt`、`make build` を最終 HEAD で直列実行する
- [x] 3.2 OpenSpec strict validation と docs/README の影響検索を実行する
- [ ] 3.3 deploy quality workflow で exact target の clean-green を確認する
