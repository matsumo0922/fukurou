## 1. Frame 読み取りの EOF 分離

- [ ] 1.1 `LlmSubmissionGatewayCodec` に「接続終了なら null を返す」frame 読み取り経路を追加し、既存の `readFrame` の契約（途中 EOF は異常）を維持する
- [ ] 1.2 size prefix 先頭の EOF と途中 EOF を区別するテストを追加する

## 2. 受付ループ

- [ ] 2.1 `submissionTask()` を accept ループ + frame ループの二重構造へ変更する
- [ ] 2.2 frame 処理中の例外を接続スコープに閉じ込め、応答可能なら `gatewayErrorResponse()` を返して次フレームを待つようにする
- [ ] 2.3 accept ループを「`accept()` の例外は種別を問わず抜ける、ループ条件は `Thread.currentThread().isInterrupted`」の規則で実装し、frame ループの例外だけを接続スコープに閉じ込める
- [ ] 2.4 `completion` を最初の要求完了時と task 終了時の双方で countDown するようにする

## 3. 状態遷移の単調性

- [ ] 3.1 `COMMITTED` を後続要求で上書きしない state 更新規則を実装する
- [ ] 3.2 「commit 後の conflict で `COMMITTED` を維持する」テストを追加する
- [ ] 3.3 「拒否のあとの受理で `COMMITTED` になる」テストを追加する

## 4. Run outcome の判定

- [ ] 4.1 `hasNoEntryEvidence()` の判定で commit 済み decision の証跡を `NO_TRADE_EXIT` より優先させる（`DecisionRunProjectionRepository.kt:111-113`、必要なら `ExposedDecisionRunProjectionRepository` の projection 側も合わせる）
- [ ] 4.2 「拒否 → 再提出で entry 受理」の run が no-entry にならないテストを追加する
- [ ] 4.3 「拒否のみで受理なし」の run が従来どおり no-entry のままであるテストを追加する

## 5. 回帰テスト

- [ ] 5.1 同一接続での「拒否 → 受理」が成立するテストを追加する
- [ ] 5.2 接続を張り直した「拒否 → 受理」が成立するテストを追加する
- [ ] 5.3 要求を送らずに切断しても gateway が後続接続を受け付けるテストを追加する
- [ ] 5.4 close 後に socket へ接続できず、worker thread が終了しているテストを追加する
- [ ] 5.5 既存の in-flight close / commit 後 completion 喪失 / start 失敗 cleanup のテストが無変更で通ることを確認する
- [ ] 5.6 canary の `awaitCompletion()` 経路が 1 要求で解除されることを確認する

## 6. 検証

- [ ] 6.1 `make detekt` を通す
- [ ] 6.2 `make test` を通す
