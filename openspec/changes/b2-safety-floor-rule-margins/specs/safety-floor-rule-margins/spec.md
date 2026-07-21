## ADDED Requirements

### Requirement: 拒否判定の不変

margin の観測は、SafetyFloor の verdict を生む評価経路から独立していなければならない（MUST）。拒否するか通過させるかの判定と、拒否時に選ばれる `SafetyFloorRule` は、margin 観測の導入によって変化してはならない（MUST NOT）。

margin 観測は `evaluatePlaceOrder` の評価順序と early-return の挙動を変更してはならない（MUST NOT）。

#### Scenario: 複数ルールが同時に FAIL する

- **WHEN** 1 つの `place_order` に対し `STOP_LOSS_REQUIRED` と `MAX_TOTAL_EXPOSURE` の両方が FAIL する
- **THEN** SafetyFloor は宣言順で先に評価される `STOP_LOSS_REQUIRED` を拒否理由とする `Rejected` を返す
- **AND** 記録される観測には両ルールの FAIL が含まれる

#### Scenario: 全ルールが PASS する

- **WHEN** どのルールも FAIL しない `place_order` を評価する
- **THEN** SafetyFloor は `Accepted` を返す

#### Scenario: 観測が例外を投げる

- **WHEN** ある evaluation point の観測が例外を投げる入力を与える
- **THEN** verdict は margin 観測を導入する前と同一である
- **AND** その evaluation point の status は `NA` として記録される
- **AND** 例外は呼び出し元へ伝播しない

### Requirement: 観測の単位

観測は rule 単位ではなく evaluation point 単位で記録しなければならない（MUST）。evaluation point は `(評価経路, rule, point 識別子)` で一意に定まる（MUST）。

1 つの rule が複数の独立した条件から構成される場合、条件ごとに evaluation point を分けなければならない（MUST）。単位の異なる量を 1 つの margin へ統合してはならない（MUST NOT）。

#### Scenario: 複数の validator が同じ rule を出す

- **WHEN** `BALANCE_RATE_AND_COST_LIMIT` を fee 検査と cash 検査の双方が評価する
- **THEN** fee 側と cash 側は別々の evaluation point として記録される

#### Scenario: sticky state と数値閾値の OR

- **WHEN** `MAX_DRAWDOWN_HALT` を評価する
- **THEN** sticky な halt state と数値閾値は別々の evaluation point として記録される

#### Scenario: 単位の異なる条件

- **WHEN** `NO_AVERAGING_DOWN` を評価する
- **THEN** 含み損（jpy）と価格差（jpy/BTC）は別々の evaluation point として記録される
- **AND** 両者を 1 つの margin へ統合しない

### Requirement: 観測と verdict の双方向整合検出

verdict が `Rejected(rule = R)` を返したとき、R に対応する evaluation point の少なくとも 1 つの status は `FAIL` でなければならない（MUST）。`SafetyViolation` は拒否を生んだ evaluation point を識別しないため、この方向の検査は rule 単位に留まる。

verdict が `Accepted` を返したとき、その経路の全 evaluation point の status は `FAIL` であってはならない（MUST NOT）。`Accepted` は全 validator の通過を意味するため、観測側の `FAIL` は early-return では説明できず、述語の乖離を示す。

いずれかの関係が破れた場合、レコードに乖離を示す印を残しログに記録しなければならない（MUST）。乖離の検出は verdict を変更してはならない（MUST NOT）。

#### Scenario: 拒否ルールが観測でも FAIL する

- **WHEN** verdict が `MAX_TOTAL_EXPOSURE` で `Rejected` を返す
- **THEN** 対応する evaluation point の status は `FAIL` である
- **AND** 乖離の印は立たない

#### Scenario: 通過したのに観測が FAIL する

- **WHEN** verdict が `Accepted` を返し、いずれかの evaluation point の status が `FAIL` である
- **THEN** レコードに乖離を示す印が立つ
- **AND** 乖離がログに記録される
- **AND** 注文処理は verdict に従って続行される

#### Scenario: 拒否ルールより後段の FAIL

- **WHEN** verdict が `STOP_LOSS_REQUIRED` で `Rejected` を返し、後段の `MAX_TOTAL_EXPOSURE` も観測で `FAIL` である
- **THEN** 乖離の印は立たない


### Requirement: 全 evaluation point の status 記録

観測は評価した decision ごとに、その経路が対象とする全 evaluation point について `PASS` / `FAIL` / `NA` のいずれかを記録しなければならない（MUST）。記録は accepted / denied の双方で行う（MUST）。

`NA` は、その evaluation point が構造的に評価不能であったこと（必要な入力が存在しない、事前条件が満たされない、または評価が例外で完了しなかったこと）を表す。`NA` を `PASS` として記録してはならない（MUST NOT）。

#### Scenario: 通過した decision の記録

- **WHEN** `place_order` が SafetyFloor を通過する
- **THEN** その経路が対象とする全 evaluation point の status が 1 レコード群として保存される

#### Scenario: 評価不能な evaluation point

- **WHEN** 対象 position が存在しない状態で pyramid 系の evaluation point を観測する
- **THEN** それらの status は `NA` として記録される
- **AND** `NA` の理由が判別できる形で保存される

#### Scenario: 経路の対象外ルール

- **WHEN** `place_order` を観測する
- **THEN** update 専用のルール（`ATR_TRAILING_FLOOR`、`IMMEDIATE_STOP_TRIGGER`）はレコードに含まれない
- **AND** それらは `PASS` としても `NA` としても記録されない

### Requirement: 事前条件が崩れた evaluation point の NA 化

ある evaluation point の評価が、別の条件の成立を暗黙の前提としている場合、その前提が満たされないときは値を算出せず `NA` として記録しなければならない（MUST）。前提が崩れた状態で算出した値を `PASS` または `FAIL` として記録してはならない（MUST NOT）。

この要件は、意味を持たない値が margin として保存され、閾値調整の判断材料を汚染することを防ぐ。

#### Scenario: stop が entry を上回る場合の EV 系

- **WHEN** protective stop が entry 価格以上である `place_order` を観測する
- **THEN** `NON_POSITIVE_EXPECTED_VALUE` と `EXPECTED_VALUE_GATE` の status は `NA` として記録される
- **AND** これらは `FAIL` として記録されない

#### Scenario: 対象 position が存在しない場合の pyramid 系

- **WHEN** 対象 trade group に open position が存在しない `place_order` を観測する
- **THEN** pyramid 系の evaluation point の status は `NA` として記録される
- **AND** 観測は例外を投げない

#### Scenario: trade group が指定されていない新規注文

- **WHEN** trade group が指定されていない `place_order` を観測する
- **AND** 無関係な open position が存在する
- **THEN** `STOP_LOSS_LOOSENING` の status は `NA` として記録される
- **AND** 無関係な position を用いて `FAIL` としない

#### Scenario: stop が未設定の position が混在する

- **WHEN** 対象 position の一部のみ現在 stop が未設定である
- **THEN** `STOP_LOSS_LOOSENING` は stop が設定された position のみで評価される
- **AND** status は `NA` にならない

#### Scenario: fee が数値として解釈できない場合の cost 系

- **WHEN** symbol の fee 文字列が数値として解釈できない `place_order` を観測する
- **THEN** cost 計算に依存する evaluation point の status は `NA` として記録される
- **AND** 観測は例外を投げない

### Requirement: margin の記録

数値 margin が定義されている evaluation point について、観測は status が `PASS` または `FAIL` のとき margin 値を記録しなければならない（MUST）。margin の符号は、正が閾値までの余裕、負が違反量を表すものとして統一しなければならない（MUST）。

margin の定義、式、単位、境界は evaluation point ごとに design.md の棚卸し表を正本とする。margin を持たないと分類された evaluation point は status のみを記録する（MUST）。

#### Scenario: 余裕をもって通過した evaluation point

- **WHEN** group risk が上限の 50% である `place_order` を評価する
- **THEN** `MAX_RISK_PER_TRADE` の status は `PASS`、margin は正の値として記録される

#### Scenario: 違反した evaluation point

- **WHEN** group risk が上限を超える `place_order` を評価する
- **THEN** `MAX_RISK_PER_TRADE` の status は `FAIL`、margin は負の値として記録される

#### Scenario: margin を持たない evaluation point

- **WHEN** `MISSING_TARGET_PRICE` を評価する
- **THEN** status のみが記録され、margin は欠測として保存される

#### Scenario: 境界ちょうどの値

- **WHEN** calendar の有効期限ちょうどの時刻で `FOMC_CALENDAR_EXPIRED` を観測する
- **THEN** status は `PASS` として記録される

### Requirement: margin 収集の取引判断からの隔離

margin の算出および永続化の失敗は、SafetyFloor の verdict を変更してはならない（MUST NOT）。永続化の失敗はログに記録し、注文処理は verdict に従って続行する（MUST）。

coroutine のキャンセルは観測の失敗として扱ってはならない（MUST NOT）。キャンセルを捕捉して注文処理を続行させてはならない（MUST NOT）。

観測の書き込みは、注文処理を長時間ブロックしてはならない（MUST NOT）。

#### Scenario: 永続化が失敗する

- **WHEN** margin レコードの保存が DB エラーで失敗する
- **AND** その decision の verdict が `Accepted` である
- **THEN** 注文は通常どおり処理される
- **AND** 失敗がログに記録される

#### Scenario: 観測中にキャンセルされる

- **WHEN** margin の永続化中に coroutine がキャンセルされる
- **THEN** キャンセルは観測の失敗として握り潰されない
- **AND** 注文処理は続行されない
- **AND** 呼び出し元には失敗として返る

#### Scenario: margin 算出が例外を投げる

- **WHEN** ある evaluation point の margin 算出が例外を投げる
- **THEN** その margin は欠測として記録される
- **AND** verdict は margin 算出の成否によらず決まる

### Requirement: risk-reducing 後処理の非遅延

観測の書き込みは、拒否後の risk-reducing な後処理を遅延させてはならない（MUST NOT）。実注文の呼び出し地点で verdict が `Rejected` かつ HARD_HALT を要求する場合、HARD_HALT の有効化と open risk の掃引を先に行ってから観測を書き込む（MUST）。

この要件は実注文の呼び出し地点にのみ適用する。preview は拒否結果を返すだけで HARD_HALT の有効化も掃引も行わないため、観測のために preview から掃引を起動してはならない（MUST NOT）。preview に ledger 変更の副作用を持たせることは、安全床の意味論の変更にあたる。

#### Scenario: HARD_HALT を伴う拒否

- **WHEN** `place_order` が HARD_HALT を要求する違反で拒否される
- **THEN** HARD_HALT の有効化と open risk の掃引が先に実行される
- **AND** その後に観測が書き込まれる

#### Scenario: preview での HARD_HALT 拒否

- **WHEN** HARD_HALT 状態で `preview_order` が拒否される
- **THEN** 観測は verdict 取得の直後に書き込まれる
- **AND** HARD_HALT の有効化も open risk の掃引も起動されない

#### Scenario: 掃引が失敗する

- **WHEN** HARD_HALT を伴う拒否で open risk の掃引が例外で終了する
- **THEN** 観測は掃引の後に 1 回試みられる
- **AND** 掃引の例外は呼び出し元へ伝播する
- **AND** 観測の失敗が掃引の例外を覆い隠さない

### Requirement: 評価経路と呼び出し地点の記録

観測レコードは、評価経路に加えて呼び出し地点（preview か実注文か）を区別できなければならない（MUST）。`evaluatePlaceOrder` は preview と実注文の双方から呼ばれるため、両者を混同すると分布が歪む。

#### Scenario: preview の観測

- **WHEN** `preview_order` が SafetyFloor を評価する
- **THEN** 観測レコードが保存される
- **AND** 実注文の観測と区別できる

### Requirement: 欠測の観測可能性

観測の**算出**が失敗し、かつ永続化先が利用可能な場合、その decision について観測を試みた事実を SQL で判別できる形で残さなければならない（MUST）。

永続化そのものが失敗した場合、SQL による判別は保証しない。同じ永続化先へ欠測レコードを書くこともできないためである。この場合はログのみとする。

この要件は、「margin が取れなかった decision」と「そもそも存在しない decision」を、永続化が生きている限り区別可能にする。

#### Scenario: 算出が全面的に失敗し DB は生きている

- **WHEN** ある decision について observer が全 evaluation point の算出に失敗する
- **AND** 永続化先が利用可能である
- **THEN** その decision の report レコードは欠測を示す状態で保存される
- **AND** SQL で欠測した decision を数えられる

#### Scenario: 永続化そのものが失敗する

- **WHEN** DB が停止している
- **THEN** 欠測レコードの保存も行われない
- **AND** 失敗がログに記録される
- **AND** 注文処理は verdict に従って続行される

### Requirement: 閾値の同一性

観測は、verdict の評価に用いられたものと同一の閾値設定を用いなければならない（MUST）。観測が独自に既定値を構築して margin を算出してはならない（MUST NOT）。

閾値が食い違うと、status は双方一致したまま margin だけが誤った値になり、乖離検査では検出できない。

#### Scenario: runtime config で閾値が変更されている

- **WHEN** 既定値と異なる閾値が runtime config で有効になっている
- **AND** その閾値のもとで evaluation point が `PASS` する
- **THEN** 記録される margin は有効な閾値に基づく値である

### Requirement: 観測 schema version の記録

観測レコードは、どの範囲の情報を保持しているかを示す schema version を含まなければならない（MUST）。margin を保持しない世代のレコードと、保持する世代のレコードを SQL で区別できなければならない（MUST）。

過去のレコードへ後から margin を backfill してはならない（MUST NOT）。観測時点の context は失われており、再構成した値は観測ではない。

#### Scenario: margin を持たない世代のレコード

- **WHEN** margin を保持しない世代で保存された numeric な evaluation point のレコードを照会する
- **THEN** schema version により margin を保持する世代のレコードと区別できる
- **AND** margin 分布の照会から除外できる

### Requirement: policy version の記録

観測レコードは、評価に用いた SafetyFloor policy version を含まなければならない（MUST）。閾値定数が変更された場合、policy version は変更されなければならない（MUST）。

#### Scenario: 閾値変更をまたぐ照会

- **WHEN** SafetyFloor の閾値を変更した前後の観測レコードを SQL で照会する
- **THEN** policy version により両者を区別できる

### Requirement: 観測レコードの SQL 照会

観測レコードは、decision 単位および evaluation point 単位で SQL により照会できなければならない（MUST）。照会には専用の UI やダッシュボードを要求しない。

#### Scenario: evaluation point 別の margin 分布を得る

- **WHEN** ある evaluation point と期間を指定して観測レコードを SQL で照会する
- **THEN** その期間の status と margin の一覧が得られる

### Requirement: 観測レコードの原子性

1 つの decision の観測は、report と全 evaluation point のレコードを不可分に保存しなければならない（MUST）。一部の evaluation point だけが保存された状態を残してはならない（MUST NOT）。

途中まで保存された観測は、SQL 上で完全な観測と区別できず、status と margin の分布を歪める。

#### Scenario: 途中で保存が失敗する

- **WHEN** report の保存に成功し、一部の evaluation point の保存で失敗する
- **THEN** その decision の観測レコードは 1 件も残らない
- **AND** 注文処理は verdict に従って続行される

### Requirement: 台帳の不変

観測レコードの書き込みは、paper ledger、cash、position、strategy PnL を変更してはならない（MUST NOT）。書き込みは専用テーブルに閉じる（MUST）。

#### Scenario: 観測書き込み前後の台帳

- **WHEN** 観測レコードを書き込む
- **THEN** cash、position、strategy PnL は書き込み前と同一である
