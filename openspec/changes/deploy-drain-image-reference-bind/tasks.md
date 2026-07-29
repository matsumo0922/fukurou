## 1. Executor の bind 位置修正

- [ ] 1.1 `scripts/deploy/deploy-fukurou` の `drain_launches()` で、`docker compose stop` を実行する直前に `bind_image_reference` を呼ぶ
- [ ] 1.2 強制停止後の PID 0 確認 → active launch interrupt → drain 完了の順序が変わっていないことを確認する

## 2. Contract test の順序 assertion

- [ ] 2.1 `ReleaseDeployFoundationContractTest` に `extractFunctionBody(executor, startMarker, endMarker)` ヘルパーを追加する。`startMarker`（例: `drain_launches() {`）の一意性は `split`/`indexOf` による**部分文字列カウントでは判定しない**（`undrain_launches() {` のような別関数宣言に部分一致して decoy を回避できてしまうため）。代わりに `Regex("(?m)^${Regex.escape(startMarker)}$")` で**行全体の厳密一致**を `findAll` し、マッチが厳密に 1 件であることを `assertEquals` で assert してから、そのマッチ位置を起点に本文を取る。`endMarker` は `indexOf` が `-1` でないことを assert してから切り出す（NUL センチネルは reviewability を損なう＝git diff がバイナリ扱いされるため使わない。design.md Decision 4 反例A・reviewability・wrong-occurrence 指摘対応）。これで `drain_launches() {`〜`\n}\n\ngap_event_payload_hash` と `compose_cutover() {`〜`\n}\n\nresume_launches_idempotently` の各本文を取得する
- [ ] 2.2 `executableLines(body)` ヘルパー（トリム・空行と `#` コメント行を除外）と、`bind_image_reference` の呼び出し判定（トリム行の**厳密一致** `line == "bind_image_reference"`。`contains` は heredoc/文字列リテラルに誤マッチするため使わない）、`docker compose` invocation 判定（正規表現 `^timeout \d+ docker compose(?=\s|$)` によるトリム行マッチ。`\b` ではなく先読みを使う。`\b` だと `docker compose-fake` のような別コマンド名にも境界が成立してしまうため。design.md Decision 4 反例 B・6回目反証指摘対応）を追加する
- [ ] 2.3 `drainLaunches` / `composeCutover` それぞれで、`docker compose` invocation が厳密に 1 件であること、`bind_image_reference` 呼び出しのインデックスが `-1` でないこと、かつ**その直後の実行行**（`bindIndex + 1`）が `docker compose` invocation のインデックスと一致すること（隣接）を assert する。単なる `<`（前にあればよい）にすると、`if false; then bind_image_reference; fi` のような通常の条件分岐リファクタで bind が実行されなくても assertion が通ってしまうため使わない（design.md Decision 4 反例C対応）
- [ ] 2.3b `bind_image_reference` 行の**直前**の実行行が `&&`/`||`/`|`/`|&`/行末 `\` のような継続演算子（継続演算子の直後の inline comment を許容する）で終わっていないことを assert する（`bindIndex - 1` が範囲外なら無条件で true）。`false &&` や `printf x |` の直後に bind を単独行で置くと、隣接判定だけでは bind が条件付き・別 subshell 実行になっていることを検出できないため。このリポジトリ自身が `[[ ... ]] && return 0` のような `&&` 短絡評価イディオムを使っているため、意図的な難読化ではなく現実的な回帰パターンとして扱う（design.md Decision 4 反例D・6回目/7回目反証指摘対応）
- [ ] 2.4 executor 全体（`executableLines(executor)`）で `docker compose` invocation が厳密に 2 件であることを count assert し、2.3 で数えた 2 箇所の合計と一致することを保証する
- [ ] 2.5 強制停止後の PID 0 確認 → active launch interrupt → drain 完了という既存順序（`assert_application_pid_zero` → `interrupt-active-launches` → drain ループ）について、各 marker の `indexOf` が `-1` でないことを先に assert してから順序比較する（先頭 marker 欠落時に `-1 < positive` が偶然成立する回避策）
- [ ] 2.6 既存の `executor keeps a straight digest pinned cutover flow` と `paused state preserves one maintenance incident until gap close` が引き続き通ることを確認する
- [ ] 2.7 heredoc/quote された文字列内に `bind_image_reference` や `docker compose` という文字列が紛れ込むケースは、shell の字句解析を要するため本 change では対応しない（design.md Decision 4 の残存リスク受容を参照）。実装時に新たな heredoc/quote を `drain_launches`/`compose_cutover` へ追加しない

## 3. ドキュメント

- [ ] 3.1 `docs/deploy.md` に本修正で誤りになる記述がないか確認し、あれば同じ差分で更新する

## 4. 検証

- [ ] 4.1 `make test` を実行して通す
- [ ] 4.2 `make detekt` を実行して通す
