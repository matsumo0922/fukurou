# linux-ci-fixture-portability Specification

## Purpose

Deploy-gated test fixture が Linux CI と対応する macOS 開発環境で production の process・path contract を再現し、platform 固有の偶然に依存せず bounded time で判定できる状態を保つ。

## Requirements

### Requirement: Linux fixtures use production process and path contracts
Deploy-gated test fixture は Linux 上で production と同じ supervisor completion contract と gateway socket path selection を MUST 使用し、platform-specific temp path assumption に依存してはならない。

#### Scenario: Timed-out supervised process exits with descendants
- **WHEN** Linux process-tree recovery fixture が child を起動して timeout termination を受ける
- **THEN** fixture root は production supervisor の completion contract を返し、process-tree exit proof は `PROVEN_EXITED` になる
- **AND** child PID は bounded observation の後に live ではない

#### Scenario: Gateway startup path is blocked
- **WHEN** gateway-start failure fixture が Linux または macOS で実行される
- **THEN** production selection が選ぶ exact socket path が妨害され、standard phase process は起動しない
- **AND** risk-reduction-only process だけが起動する

#### Scenario: Fixture exits through failure
- **WHEN** assertion または gateway startup が途中で失敗する
- **THEN** fixture が作成した socket blocker とその child artifact は `finally` 境界で削除される

### Requirement: Fixture waits are bounded and non-racy
OS process または file の観測待機は monotonic deadline と最後の observation を MUST 持ち、対象を観測した時点で終了しなければならない。

#### Scenario: Valid positive PID appears before deadline
- **WHEN** child PID file が deadline 前に正の整数 PID として parse 可能になる
- **THEN** wait は直ちに PID を返し、process timeout 時刻まで固定反復を継続しない

#### Scenario: Valid positive PID does not become observable
- **WHEN** child PID file が deadline まで存在しない、read できない、または正の整数 PID として parse できない
- **THEN** test は bounded time で path、existence、最後の bounded content と parse state を含む失敗を返す
