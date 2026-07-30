## Context

`backup-fukurou` は `pg_dump | restic backup --stdin` のパイプラインを、独立 watchdog（backend PID 追跡 + `pg_terminate_backend` + result file + 60 秒 deadline 検証）で監視している。2026-07-30、この watchdog が健全な pg_dump を誤終了させ（issue #336 の trace で確定）、backup が恒常失敗し main の全デプロイが停止した。

失敗の機序: `restic_command()` の `--no-cache` 固定により、snapshot が蓄積した本番 repo では restic が backup 開始時の index 読み込みに時間を要し、その間 stdin を消費しない。パイプバッファが詰まり pg_dump が stall し、55 秒（60 − grace 5）で watchdog が backend を terminate する。実測: pg_dump 単体 14 秒、空 repo へのパイプライン 9.3 秒、本番 repo では 55 秒で kill。

## Goals / Non-Goals

**Goals:**

- backup 自身が database backend を終了させる機構を撤去し、時間制限を呼び出し側（deploy の `timeout 900` / systemd の `TimeoutStartSec=20min`）に一本化する
- restic local cache を使い、蓄積 repo での index 読み込み背圧を解消する
- 失敗時に pg_dump / restic の stderr が診断に使える状態にする

**Non-Goals:**

- stderr の捕捉・要約・status.json への記録機構の新設（stderr を流すだけとする）
- restic 装置全体の置き換え（暗号化・整合性検証・retention・restore drill は不変）
- repository の prune / 蓄積管理ポリシーの変更
- backup を deploy の前提から外すこと

## Decisions

### Decision 1: watchdog を撤去し、container 内側の `timeout` で dump を bound する（帰属: ユーザー確認済み — issue #336 の方針。bound の配置は反証ゲートの blocking 反例を受けた設計修正）

watchdog が守っていた実質的リスクは「pg_dump backend が lock 待ちまたはパイプ背圧で stall したまま残留し、DB のロックを保持し続ける」ことである。

初版設計は「呼び出し側の外側 timeout（deploy の `timeout 900` / systemd の `TimeoutStartSec=20min`）が dump client を kill すれば、接続喪失で backend も終了する」としていたが、独立反証で実機検証により反証された（blocking 反例）: **docker exec の host 側 client を SIGKILL しても container 内のプロセスには伝播せず、孤児として残留する**。SIGTERM は docker exec が proxy するため伝播するが、SIGKILL は proxy 不能であり、TERM に応答しない stall こそが問題のケースである。deploy 経由の `timeout 900` は TERM のみ（`--kill-after` なし）、systemd の `KillMode=control-group` も host 側プロセスの掃討しか保証しない。

修正後の設計: **dump の bound を container 内側に置く**。

```
docker exec ... "${PRODUCTION_CONTAINER_ID}" timeout --signal=TERM --kill-after=10 840 pg_dump ...
```

- container 内の `timeout` は host 側 client の生死と無関係に pg_dump を終了させられるため、孤児化が構造的に不可能になる。TERM で pg_dump が接続を閉じれば backend も終了し、TERM 不応答でも `--kill-after` の SIGKILL は同一 PID namespace 内なので必ず届く
- pg_dump プロセス自身が死ねば backend は接続喪失で終了する（PostgreSQL の保証）。`pg_terminate_backend` による能動的終了・PID 追跡・application_name 照合・再確認ループ・result file という watchdog の複雑性は丸ごと不要のまま
- bound 値は 840 秒とする。restic 側の背圧を含むパイプライン全体の実測（9.3 秒）に対して 90 倍の余裕があり、deploy の外側 `timeout 900` より内側で先に発火する。旧 watchdog の 60 秒のような「実行時間の予測」に基づくタイトな値を避け、「無限 stall の防止」だけを目的とする
- `--lock-wait-timeout` は導入しない: lock 待ちは閉じるがパイプ背圧 stall は閉じないため、対策として不完全。container 内 timeout が両方を一様に閉じる

トレードオフ: 旧 watchdog は 60 秒で backend を終了させたが、新設計では stall 時の解放が最大 840 秒まで遅れうる。single-owner の paper trading DB では、この遅延で失われるものはない（daemon は deploy 中 pause 済み、backup は日次 1 回）。

### Decision 2: `--no-cache` を撤去し、cache の場所を `/var/cache/fukurou-restic` に固定する（帰属: agent 仮決め — 根拠は issue #336 の実測。cache 場所はレビュー指摘 F1 対応）

`--no-cache` の選択根拠は docs / openspec / コミット履歴のいずれにも記録されていない。restic の local cache は暗号化 repo のデータの複製であり、平文の dump・DB パスワード・repo パスワードのいずれも含まないため、既存 Requirement の secret 非永続化条項に抵触しない。cache が壊れた場合 restic は自動で repo から再構築する（cache は正本ではない）。

cache の場所: restic の既定（`$HOME/.cache/restic` = root では `/root/.cache/restic`）は、backup unit の `ProtectHome=yes` が `/root` を不可視化するため systemd 経由では書けない（レビューで検出。restic は cache 作成失敗時に cache なしへ fallback するため機能は壊れないが、背圧解消という本 change の目的が本番経路で達成されない）。unit に `CacheDirectory=fukurou-restic` を追加して `/var/cache/fukurou-restic` を割り当て、`RESTIC_CACHE_DIR` で明示する。`backup-fukurou` 側も同じパスを既定にし、deploy 経由（unit 外）でも同一 cache を共有する。`/var/cache` は `ProtectSystem=strict` 下でも `CacheDirectory` 指定で書き込み可能になる。

### Decision 3: stderr は「破棄をやめる」だけにする（帰属: ユーザー確認済み — issue #336 の Scope 外に記録機構新設を明記）

pg_dump の `2>/dev/null` と restic の `2>/dev/null` を削除し、stderr をそのまま呼び出し元へ流す。deploy 経由では GitHub Actions のログに、systemd 経由では journal に自然に残る。pg_dump / restic の stderr には接続エラーやパス情報は出るがパスワードは出ない（PGPASSWORD は env 経由ではなく container 内の trust 接続、restic は RESTIC_PASSWORD_FILE 経由で値を echo しない）。

### Decision 4: result code `WATCHDOG_TERMINATION_FAILED` を削除し、`DUMP_FAILED` の意味を維持する（帰属: agent 仮決め）

`DUMP_FAILED` は「producer（pg_dump）の exit が非 0」という分類として watchdog なしでも意味が変わらないため残す。`WATCHDOG_TERMINATION_FAILED` は発生源が消えるため `backup-result-codes-v1.txt` から削除する。result code ファイルは installer が配置する運用 artifact のため、merge 後の root-owned artifact 更新に含める。

### Decision 5: selftest / contract test は削除に追随する（帰属: agent 仮決め）

`backup-selftest` の watchdog 関連 assertion（約 39 箇所）は検証対象が消えるため削除する。「watchdog なしで backend が残留しないこと」の新規テスト harness は追加しない（外側 timeout + PostgreSQL の接続喪失時 backend 終了は PostgreSQL 自体の保証であり、それを再検証するテストは Epic #286 が撤去してきた儀式的検証になる）。`DatabaseBackupRestoreContractTest` は Docker-backed の実行契約（entrypoint 実行、retention、redacted output）を検証しており、watchdog 固有の assert は持たないことを確認済み — 変更は selftest 側に限られる見込みで、実装時に差分が出た場合のみ追随する。

## Risks

- **lock 待ち backend の解放が最大 20 分（systemd 経由）まで遅れうる**: 上記 Decision 1 のトレードオフ。paper trading の実験スループットへの影響は日次 backup の失敗 1 回分に留まる。
- **cache 導入による初回実行の挙動変化**: 初回は cache 構築で従来よりわずかに遅くなりうるが、外側 timeout（900 秒）に対して問題にならない。
- **stderr にパス情報が出る**: repo パス（`/srv/fukurou/backups/postgres`）等の非 secret 情報が deploy ログに出るようになる。secret ではないため許容する。

## Next steps（この change に含めない）

- restore 側（`restore-fukurou` / `restore-selftest`）の `--no-cache` 方針の見直し（restore drill は使い捨て環境での読み取りで背圧問題が存在しないため、本 change では現状維持とした）
- restic 装置全体の `pg_dump | zstd > file` 方式への置き換え評価（issue #336 の Scope 外として記録済み）
