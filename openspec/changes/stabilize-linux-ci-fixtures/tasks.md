## 1. Population bound fixture

- [x] 1.1 同じ period の1件 scoped + 20,001件 scope外で正常 scoped aggregation を証明する
- [x] 1.2 同じ20,002件の global population に対する別の assertion で entity limit 超過の typed failure を証明する
- [x] 1.3 targeted Postgres test の成功と実行時間を記録する

## 2. Linux process and gateway fixtures

- [x] 2.1 recovery child を supervisor completion contract と valid positive PID の bounded observation に合わせる
- [x] 2.2 gateway-start fixture で production が選ぶ exact socket path を妨害する
- [x] 2.3 socket blocker を成功・失敗の両経路で cleanup する
- [x] 2.4 Linux container で supervisor と gateway を実検証し、macOS で gateway の実検証と supervisor の skip status を確認する

## 3. Validation and documentation

- [x] 3.1 `make test`、`make detekt`、`make build` を最終 HEAD で直列実行する
- [x] 3.2 OpenSpec strict validation と docs/README の影響検索を実行する
- [ ] 3.3 deploy quality workflow で exact target の clean-green を確認する

## 4. Review corrections

- [x] 4.1 PID file existence ではなく valid positive PID parse を bounded poll し、deadline diagnostic を拡張する
- [x] 4.2 population と platform の artifact claim、test 名、test-only socket oracle の disposition を実装へ揃える
- [x] 4.3 validation lease と isolated Gradle home で Linux container / macOS targeted tests、detekt、OpenSpec strict、diff-check を完了する
