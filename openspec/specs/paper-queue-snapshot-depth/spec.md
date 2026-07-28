# paper-queue-snapshot-depth Specification

## Purpose

paper resting BUY LIMIT の queue_ahead が観測する orderbook depth の範囲と、その範囲外に対する fail-closed 境界を定める。queue_ahead は paper 約定の因果的入力であり、その観測範囲は paper 約定価格の算出が使う depth とは独立する。

## Requirements
### Requirement: Queue snapshot observes the full returned bid depth

Issue #320: paper resting BUY LIMIT の queue_ahead 算出は、取引所が返した bid levels を client 側で切り詰めずに観測する。system SHALL queue_ahead 専用の orderbook depth を持ち、その値は GMO Public API が 1 response で返しうる bid levels 数以上とする。system SHALL NOT この depth を paper 約定価格の算出（MARKET / LIMIT taker の slippage walk、SafetyFloor の板参照）に流用する。

#### Scenario: Limit price sits outside the legacy 50-level window but inside the returned depth

- **WHEN** resting BUY LIMIT の指値が best bid から 50 levels より深く、かつ取引所が返した bid levels の最深値以上である
- **THEN** 注文は同一価格の exchange bid 数量と先行する同価格の自 paper order 数量を合算した `queueAheadBtc` を伴って受理される

#### Scenario: Limit price sits outside the entire returned depth

- **WHEN** resting BUY LIMIT の指値が、取引所が返した bid levels の最深値より低い
- **THEN** system は `QUEUE_SNAPSHOT_UNAVAILABLE` として注文を作らず、observed していない価格レベルの queue を 0 とみなさない

#### Scenario: Fill price simulation keeps its own depth

- **WHEN** paper の MARKET 約定、LIMIT taker 約定、または SafetyFloor 評価が板を参照する
- **THEN** それらは queue_ahead 用 depth ではなく既存の paper execution depth を使い、depth 枯渇時の fallback 条件と約定価格は本 requirement によって変化しない

### Requirement: Public market-data client accepts the queue snapshot depth

Issue #320: `MarketDataSource.getOrderbook` の depth 上限は、queue_ahead が要求する depth を拒否しない。system SHALL GMO Public market-data client の depth 上限を queue snapshot depth 以上とする。system SHALL NOT depth 拡大に伴って追加の HTTP request を発行する。

#### Scenario: Queue snapshot requests the maximum depth

- **WHEN** queue_ahead 算出が queue snapshot depth で orderbook を要求する
- **THEN** client は上限違反として拒否せず、1 回の orderbook request の response から bid / ask levels を返す

#### Scenario: LLM-facing orderbook tool keeps its own limit

- **WHEN** LLM が MCP の orderbook tool で板を取得する
- **THEN** tool 側の depth 上限は本 requirement によって変化せず、prompt 面の観測範囲は独立に決まる

