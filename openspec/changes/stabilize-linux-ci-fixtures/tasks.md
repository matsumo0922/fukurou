## 1. Population bound fixture

- [ ] 1.1 正常 scoped aggregation を最小 population で証明する
- [ ] 1.2 entity limit 超過を安価な独立 population で作り typed failure を証明する
- [ ] 1.3 targeted Postgres test の成功と実行時間を記録する

## 2. Linux process and gateway fixtures

- [ ] 2.1 recovery child を supervisor completion contract と bounded PID observation に合わせる
- [ ] 2.2 gateway-start fixture で production が選ぶ exact socket path を妨害する
- [ ] 2.3 socket blocker を成功・失敗の両経路で cleanup する
- [ ] 2.4 Linux container と macOS で担当二テストを targeted 検証する

## 3. Validation and documentation

- [ ] 3.1 `make test`、`make detekt`、`make build` を最終 HEAD で直列実行する
- [ ] 3.2 OpenSpec strict validation と docs/README の影響検索を実行する
- [ ] 3.3 deploy quality workflow で exact target の clean-green を確認する
