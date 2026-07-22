# gate-shadow-counterfactual Specification

## Purpose

production paper trading で TTL 失効した entry について、失効後の市場 event が注文境界を跨いだかを因果境界付きで観測し、paper 約定を遡及生成せず `CROSSED` / `UNKNOWN` として評価する仕様を定める。

## Requirements
### Requirement: TTL 失効した entry の geometry と因果境界を失効 transaction 内で捕捉する

システムは、production（Exposed）経路で TTL 失効（resting entry の TTL 超過 cancel）した LIMIT / STOP long entry について、失効 ledger transaction 内で order の geometry・`order_id`・`market_data_session_id`・失効時点の admission watermark（`start_admission_ordinal`）・`window_start_time`（失効時刻）を immutable な payload として捕捉し、append-only の観測レコードとして保存しなければならない（MUST）。

対象は production 経路の TTL 失効の LIMIT / STOP long entry に限定する。EV 拒否その他の gate、in-memory ledger 経路、MARKET order type、非 BUY は対象にしない（SHALL NOT）。

`start_admission_ordinal` は、commit 後に読む high-watermark ではなく、失効 ledger transaction 内（event admission と同じ session 直列化が効く地点）で確定した値でなければならない（MUST）。ただし ordinal watermark 単独では因果境界を保証できない（receipt admission は fence と同一 lock を取らないため、失効前に観測されたが fence 後に採番される event が `admission_ordinal > start_admission_ordinal` を満たしうる）。因果境界は **`admission_ordinal` 下界と `socket_observed_at >= window_start_time` の複合** で完成し、分類はこの両方を課さなければならない（MUST。「分類対象は因果境界より後」Requirement 参照）。

捕捉は best-effort とする。捕捉の失敗は失効・注文処理の判定を変えてはならず（MUST NOT）、risk-reducing な cancel を巻き戻してはならない（MUST NOT）。捕捉の取りこぼしは、正本（`cancel_reason=TTL_EXPIRY` かつ LIMIT/STOP の order 行）に対する `order_id` join で SQL で算出できなければならない（MUST）。

#### Scenario: TTL 失効時に geometry と因果境界を捕捉する

- **WHEN** production 経路で LIMIT / STOP の resting entry が TTL 超過で cancel される
- **THEN** その注文の geometry・`order_id`・`market_data_session_id`・`start_admission_ordinal`・`window_start_time` を持つ観測レコードが 1 件書かれる
- **AND** cancel は shadow 捕捉の失敗によって巻き戻されない

#### Scenario: 捕捉に失敗しても取りこぼしを照合できる

- **WHEN** cancel の commit 後に shadow 捕捉が失敗する
- **THEN** その失効は正本（`cancel_reason=TTL_EXPIRY` の order 行）に残り、`order_id` join で観測の欠落として SQL で照合できる

### Requirement: 分類対象は因果境界より後かつ同一 session の event に限る

システムは、観測の分類対象を、`session_id = observation.market_data_session_id AND admission_ordinal > observation.start_admission_ordinal AND socket_observed_at >= window_start_time AND socket_observed_at <= window_start_time + horizon` を満たす市場 event に限定しなければならない（MUST）。`admission_ordinal` の下界（線形化・scan 起点）と `socket_observed_at >= window_start_time` の下界（因果の芯）を両方課さなければならない（MUST）。終了境界に用いる時刻は event の `socket_observed_at`（callback で確定する値）に固定しなければならない（MUST）。因果境界以前の event、別 session の event、終了境界より後に観測された event を分類に用いてはならない（MUST NOT）。receipt admission は allocation fence と同一 lock を取らないため、失効前に観測されたが fence 後に採番された event が `admission_ordinal > start_admission_ordinal` を満たしうる。この event を `socket_observed_at < window_start_time` の下界で分類から排除しなければならない（MUST NOT 分類に用いる）。本 change 導入前の既存失効への backfill を行ってはならない（MUST NOT）。

#### Scenario: 因果境界以前の event を無視する

- **WHEN** ある event の `admission_ordinal` が観測の `start_admission_ordinal` 以下である
- **THEN** その event は分類に使われない

#### Scenario: 別 session の event を無視する

- **WHEN** ある event の `session_id` が観測の `market_data_session_id` と異なる
- **THEN** その event は分類に使われない

#### Scenario: 失効前に観測された event を admission race でも無視する

- **WHEN** ある event の `socket_observed_at` が観測の `window_start_time` より前だが、admission fence 後に採番され `admission_ordinal > start_admission_ordinal` を満たす
- **THEN** その event は `socket_observed_at < window_start_time` の下界により分類に使われない

### Requirement: 約定境界の充足を陽性の観測事実として記録し、約定も非約定も断定しない

システムは、対象 event から注文の結果を `CROSSED` / `UNKNOWN` の 2 値で分類しなければならない（MUST）。`CROSSED` は「価格が注文境界を跨いだ」という観測事実にとどめ、注文が約定した、とは断定してはならない（MUST NOT）。過去の価格履歴から paper 約定を遡及作成してはならない（MUST NOT）。

約定境界は order type に依存する。LIMIT long entry は `eventPrice <= limitPrice`、STOP long entry は `eventPrice >= triggerPrice`。境界クロスを 1 件でも発見すれば `CROSSED` とし、区間の完全性は要求しない。

1 つの観測に対する resolution は 1 行とし、状態は `UNKNOWN` から `CROSSED` への一方向にのみ遷移しなければならない（MUST）。`CROSSED` は `UNKNOWN` に優先し、並行実行下でも `UNKNOWN` が `CROSSED` を上書きしてはならない（MUST NOT）。

#### Scenario: 境界を跨ぐイベントを発見する

- **WHEN** 因果境界以降・同一 session に注文境界を跨ぐ event が存在する
- **THEN** 結果は `CROSSED` になり、跨いだ event の sequence / exchangeAt / price が記録される
- **AND** 約定した旨のレコードや paper 約定は作られない

#### Scenario: 並行に UNKNOWN と CROSSED を書いても CROSSED が残る

- **WHEN** 2 つの tick が同じ観測に対し片方は `UNKNOWN`、片方は `CROSSED` を書き込む
- **THEN** 最終的な resolution は `CROSSED` になる

### Requirement: 観測は窓が settle した後に走査する

システムは、観測を `window_start_time + horizon + settlement grace` を経過するまで走査してはならない（MUST NOT）。この経過後（窓内 event が commit 済みで settle した状態）に走査しなければならない（MUST）。これにより、非同期・順不同に commit される event を走査中に取りこぼす race を避けなければならない（MUST）。

#### Scenario: settle 前は走査しない

- **WHEN** ある観測がまだ `window_start_time + horizon + settlement grace` を経過していない
- **THEN** その観測は走査されず、resolution も書かれない

### Requirement: 陽性を確認できない観測は、読み切り後にのみ UNKNOWN とする

システムは、settle した観測について、次の条件をすべて満たすときにのみ結果を `UNKNOWN` として terminal にしなければならない（MUST）: (1) `admission_ordinal` 昇順に session の現 admission high-watermark まで走査を読み切った、(2) 各 event を `socket_observed_at <= window_start_time + horizon` で窓内判定した、(3) 境界クロスを 1 件も発見していない。`socket_observed_at` が終了境界を越えた最初の event で走査を打ち切ってはならない（MUST NOT）（`admission_ordinal` 順と `socket_observed_at` 順が非単調でありうるため、その先の窓内 event を取りこぼす）。未読の event を残したまま `UNKNOWN` を確定させてはならない（MUST NOT）。admission_ordinal の欠番（rollback 由来）を gap と誤認して永久に pending にしてはならない（MUST NOT）。

個別 event の `normalized_payload` の decode 失敗は、その event を対象から外して `data_quality` に記録し、走査を継続しなければならない（MUST）。decode 失敗を理由に即座に terminal `UNKNOWN` としてはならない（MUST NOT）。`UNKNOWN` は「約定機会が無かった」を含意してはならない（MUST NOT）。`UNKNOWN` の観測を母集団から黙って除外してはならない（MUST NOT）。

#### Scenario: 読み切ってクロスが無い

- **WHEN** settle 後、窓の終了境界まで走査を読み切り、クロスが 1 件も無い
- **THEN** 結果は `UNKNOWN` になり、「未確認」として母集団に残る

#### Scenario: decode 失敗でも後続を読む

- **WHEN** ある event の decode に失敗し、その後の event が境界を跨ぐ
- **THEN** decode 失敗は `data_quality` に記録され、後続の crossing で `CROSSED` になる

### Requirement: settle 後の走査は有界で、pending 間も前進する

システムは、1 tick で 1 観測について読む event 数に上限を設けなければならず（MUST）、上限で打ち切った場合は走査位置（`last_scanned_admission_ordinal`）を永続化して次の tick が続きから再開できなければならない（MUST）。settle 済みの窓を走査するため、この cursor 前進は commit race の影響を受けてはならない（MUST NOT）。上限による打ち切りは log しなければならない（MUST）。

#### Scenario: read 上限を越える観測が前進する

- **WHEN** settle した観測の窓内に read 上限を越える event があり、その先に crossing がある
- **THEN** 複数 tick にわたって走査が前進し、最終的に crossing に到達して `CROSSED` になる

### Requirement: resolver は時間予算で有界化され、launch 遅延を予算内に bound する

システムは、resolver の 1 tick あたりの作業に wall-time 予算 N と DB statement / lock / connection 取得の timeout を課さなければならない（MUST）。予算超過・timeout のとき、cursor を壊さず pending のまま fail-open にし、次の tick に回さなければならない（MUST）。resolver は同期配置のため launch 開始を最大 N だけ遅らせうるが、その遅延を予算 N 以内に bound しなければならない（MUST）。resolver の失敗は daemon tick の他処理を変えてはならない（MUST NOT）。resolution が依存する receipts の複合 index が catalog validity（`indisvalid AND indisready AND indislive`）を満たさない間は、resolver を無効にしなければならない（MUST）。

#### Scenario: resolver が予算を超過しても launch 遅延は予算内に収まる

- **WHEN** resolver の DB 作業が wall-time 予算 N を超過する
- **THEN** resolver は pending のまま打ち切られ、launch 開始の遅延は N 以内に収まる

#### Scenario: index が有効になるまで resolver は動かない

- **WHEN** receipts の複合 index が `indisvalid AND indisready AND indislive` を満たさない（未作成・build 中・CONCURRENTLY 失敗の invalid 状態）
- **THEN** resolver は無効のままで、capture のみ動作する

### Requirement: shadow の観測と resolution は ledger を変更しない

システムは、gate-shadow の観測捕捉および resolution の書き込みが、cash / position / order / paper 約定のいずれも変更しないことを保証しなければならない（MUST）。shadow の書き込み失敗は、失効・注文処理・daemon tick の他処理を変えてはならない（MUST NOT）。

#### Scenario: shadow 書き込みが ledger を変えない

- **WHEN** gate-shadow の観測または resolution が書き込まれる
- **THEN** cash / position / strategy PnL は変化しない

### Requirement: shadow の観測と resolution は SQL で照会できる

システムは、gate-shadow の観測と resolution を、結果・時刻で SQL 照会できる形で永続化しなければならない（MUST）。

#### Scenario: 結果別に集計する

- **WHEN** 利用者が `CROSSED` / `UNKNOWN` の件数を SQL で集計する
- **THEN** 結果が返る
