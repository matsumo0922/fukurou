## Why

最新 main の deploy quality gate は macOS で成功する三つの test fixture が Linux CI では再現可能に失敗し、正しい production image の publication を阻止している。quality gate を再試行で偶然通すのではなく、検証対象の意味を保ったまま fixture の platform assumption と過大な実行時間を除く必要がある。

## What Changes

- evaluation population bound test は同じ period に1件 scoped + 20,001件 scope外を作り、正常な scoped 集計と20,002件の global oversized population rejectionを別々の assertion で証明する。
- Linux process-tree recovery test の child fixture を production supervisor protocol に合わせ、PID 観測を timeout と競合しない bounded wait にする。
- gateway-start failure test が production と同じ socket path selection を使って exact path を妨害し、成功・失敗のどちらでも artifact を cleanup する。
- product/runtime code、production JDBC timeout、deploy authority は変更しない。

## Capabilities

### New Capabilities

- `linux-ci-fixture-portability`: deploy-gated test fixture が Linux 上で production contract と同じ path/process semantics を使い、bounded time で同じ invariant を検証する契約。

### Modified Capabilities

- `postgres-test-connection-bounds`: large population test は JDBC socket timeout を test oracle にせず、entity limit の意味を bounded time で検証する。
- `deploy-quality-gate`: exact target の正しい test suite は Linux supervisor fixture を Linux CI で実検証し、gateway socket fixture を Linux と macOS で実検証する。利用できない platform fixture の skip は実検証の証拠に数えない。

## Impact

対象は `trading` module の test source と OpenSpec のみ。API、DB schema、runtime behavior、production compose、root deploy artifact への影響はない。
