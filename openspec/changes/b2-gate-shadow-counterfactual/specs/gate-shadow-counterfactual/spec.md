## ADDED Requirements

### Requirement: 遡及 fill の禁止

shadow 観測は、activation cursor より前に発生した market event を fill 判定に使ってはならない（MUST NOT）。activation cursor は拒否または失効の時点で確定させ、観測レコードに保存しなければならない（MUST）。

cursor は `admission_ordinal` と market 時刻の対とする。event は **ordinal が cursor の ordinal より大きく、かつ market 時刻が cursor の時刻以降**である場合にのみ fill 判定へ用いる（MUST）。いずれか一方でも満たさない event は、判定から除外する。

#### Scenario: activation 前に価格が touch していた

- **WHEN** 10:00 に拒否されたプランの entry 価格へ、09:55 の market event が触れている
- **THEN** その event は fill 判定に用いられない
- **AND** 観測結果は 09:55 の event を根拠に `FILLED_EQUIVALENT` とならない

#### Scenario: cursor を保存しない観測は作らない

- **WHEN** activation cursor を確定できない拒否を観測しようとする
- **THEN** 観測レコードは作成されない

#### Scenario: ordinal は後だが market 時刻が前の event

- **WHEN** cursor より大きい `admission_ordinal` を持ち、market 時刻が cursor の時刻より前の event が存在する
- **THEN** その event は fill 判定に用いられない

### Requirement: 追跡対象の限定

shadow 観測は、entry geometry（entry 価格・protective stop・take profit）が確定している拒否・失効についてのみ作成しなければならない（MUST）。geometry が確定していない NO_TRADE について、entry 条件を推定または生成してはならない（MUST NOT）。

既存 position の管理を対象とする決定（EXIT / REDUCE / ADJUST_PROTECTION）は、新規 entry plan geometry を持たないため観測対象外とする（MUST）。

#### Scenario: geometry のない NO_TRADE

- **WHEN** decision が生成されないまま NO_TRADE が記録される
- **THEN** shadow 観測は作成されない

#### Scenario: EXIT 決定の失敗

- **WHEN** EXIT 決定の実行が失敗して NO_TRADE が記録される
- **THEN** shadow 観測は作成されない

#### Scenario: Falsifier が拒否した entry

- **WHEN** Falsifier が entry intent を拒否する
- **THEN** その intent の geometry に対する shadow 観測が作成される

### Requirement: 約定の断定禁止

queue 位置が不明な観測について、約定したと断定してはならない（MUST NOT）。価格が entry 条件を跨いだことのみを根拠に `FILLED_EQUIVALENT`、`STOP_HIT`、`TP_HIT` を記録してはならない（MUST NOT）。

paper の resting entry 約定は queue 消費を条件とするため、queue 位置なしに約定の有無は決まらない。一方、価格が一度も entry 条件を跨がなかった場合は queue 位置によらず約定しないため、断定してよい（MAY）。

観測は、queue 位置が既知の場合にのみ `FILLED_EQUIVALENT` / `STOP_HIT` / `TP_HIT` を記録できる（MUST）。queue 位置が不明な場合、価格が跨いだ観測は `FILL_UNDETERMINED` として記録しなければならない（MUST）。

#### Scenario: queue 位置が不明で価格が跨いだ

- **WHEN** SafetyFloor が拒否したプランの entry 価格を市場価格が跨ぐ
- **AND** そのプランには queue snapshot が存在しない
- **THEN** 観測結果は `FILL_UNDETERMINED` として記録される
- **AND** `FILLED_EQUIVALENT` としては記録されない

#### Scenario: queue 位置が不明で価格が跨がなかった

- **WHEN** 拒否されたプランの entry 価格を市場価格が horizon 内に一度も跨がない
- **THEN** 観測結果は `NO_FILL_CERTAIN` として記録される

#### Scenario: queue 位置が既知

- **WHEN** TTL 失効した order の queue snapshot が存在する
- **AND** paper と同一の queue 消費条件を満たす event 列が存在する
- **THEN** 観測結果は `FILLED_EQUIVALENT` 以降の語彙で記録される

### Requirement: 条件付き軌跡の分離

「約定していた場合に stop と take profit のどちらへ先に到達したか」を記録する場合、outcome とは別のフィールドに保存しなければならない（MUST）。条件付き軌跡を outcome として記録してはならない（MUST NOT）。

#### Scenario: 約定不明だが軌跡は判定できる

- **WHEN** 観測結果が `FILL_UNDETERMINED` である
- **AND** 価格が entry を跨いだ後、take profit へ先に到達している
- **THEN** outcome は `FILL_UNDETERMINED` のままである
- **AND** 条件付き軌跡フィールドに `TP_FIRST` が記録される

### Requirement: 固定 observation horizon

各 shadow 観測は、作成時に固定の observation horizon を持たなければならない（MUST）。horizon を過ぎても決着しなかった観測は、その時点で確定させる（MUST）。horizon の延長や、決着するまで観測を継続することを行ってはならない（MUST NOT）。

horizon は観測レコードに保存し、後から変更してはならない（MUST NOT）。

#### Scenario: horizon 内に価格が跨がなかった

- **WHEN** horizon を過ぎても entry 条件を跨ぐ event が無い
- **THEN** 観測結果は `NO_FILL_CERTAIN` として確定する

#### Scenario: horizon 経過後の touch

- **WHEN** horizon 経過後に entry 価格へ触れる event が発生する
- **THEN** 既に確定した結果は変更されない

### Requirement: データ欠損の非決着

market data gap と交差した観測は `UNKNOWN_DATA_GAP` として記録しなければならない（MUST）。gap 期間の市場挙動を推定して決着させてはならない（MUST NOT）。

`UNKNOWN_DATA_GAP` は決着済みの母集団に含めてはならない（MUST NOT）。勝率、EV、profit factor などの集計から除外できる形で保存する（MUST）。

#### Scenario: 観測期間が gap と重なる

- **WHEN** 観測の horizon 内に未復旧の market data gap が存在する
- **THEN** 観測結果は `UNKNOWN_DATA_GAP` として記録される
- **AND** `NO_FILL_CERTAIN` としては記録されない

#### Scenario: gap の前に決着済み

- **WHEN** gap 開始より前に `STOP_HIT` が確定している
- **THEN** その後の gap は結果を `UNKNOWN_DATA_GAP` へ変更しない

### Requirement: 台帳の不変

shadow 観測および結果の書き込みは、paper ledger、cash、position、strategy PnL、order を変更してはならない（MUST NOT）。書き込みは shadow 専用テーブルに閉じる（MUST）。

shadow の結果は、実際の約定として order や execution を生成してはならない（MUST NOT）。

#### Scenario: shadow 書き込み前後の台帳

- **WHEN** shadow 観測と結果を書き込む
- **THEN** cash、position、strategy PnL は書き込み前と同一である
- **AND** order と execution は追加されない

### Requirement: policy version の記録

shadow 観測は、拒否または失効の時点で有効だった runtime config version、SafetyFloor policy version、Falsifier policy version を記録しなければならない（MUST）。

#### Scenario: 閾値変更をまたぐ照会

- **WHEN** SafetyFloor の閾値を変更した前後の shadow 観測を SQL で照会する
- **THEN** policy version により両者を区別できる

### Requirement: 観測の冪等性

同一の拒否・失効に対して、shadow 観測を重複して作成してはならない（MUST NOT）。resolver が同じ観測を複数回処理しても、結果は一度だけ確定する（MUST）。

#### Scenario: resolver の再実行

- **WHEN** 同じ観測に対して resolver が 2 回実行される
- **THEN** 結果レコードは 1 件のみ存在する

#### Scenario: 同一拒否の重複記録

- **WHEN** 同一の拒否に対して観測の作成が 2 回試みられる
- **THEN** 観測レコードは 1 件のみ存在する

### Requirement: resolver の fail-open

shadow resolver の失敗は、daemon tick、取引判断、注文処理を停止させてはならない（MUST NOT）。失敗はログに記録し、次の tick で再試行する（MUST）。

#### Scenario: resolver が例外を投げる

- **WHEN** shadow resolver が例外を投げる
- **THEN** daemon tick は継続する
- **AND** 失敗がログに記録される

### Requirement: shadow 結果の SQL 照会

shadow 観測と結果は SQL により照会できなければならない（MUST）。照会には専用の UI やダッシュボードを要求しない。

#### Scenario: ゲート別の結果分布を得る

- **WHEN** ゲート種別と期間を指定して shadow 結果を SQL で照会する
- **THEN** その期間の結果種別ごとの件数が得られる
- **AND** `UNKNOWN_DATA_GAP` が決着済みと区別できる
