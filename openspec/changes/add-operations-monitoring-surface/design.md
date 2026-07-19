## Context

Issue #190 の deploy、backup、restore は root-owned な authority と fail-closed な journal/status を持つ。一方、PR-5 の外部 alert workflow が利用できる application-readable contract はなく、現在は daemon、provider、reconciler、gap、backup/restore の証拠が DB、in-memory status、root-only JSON に分散している。

この変更は観測結果を一箇所へ集約するが、alert threshold の判定、取引制御、readiness 判定は行わない。root-only repository、password、raw provider output、event payload、host path は公開面へ出さない。

## Goals / Non-Goals

**Goals:**

- `GET /ops/monitoring` で PR-5 が必要とする時刻、件数、状態を versioned response として返す。
- source ごとの failure を隔離し、欠損や不正値を stable reason code 付き `UNKNOWN` として表現する。
- DB aggregation を固定 window と有限 query に制限する。
- root-only backup status から allowlist 済みの証拠だけを atomic projection に写す。
- systemd process が status publication 前に kill/OOM された場合も、古い success を current success と誤認しない。
- 既存 deploy を壊さず、root artifact install と mount activation を明示的に段階適用できるようにする。

**Non-Goals:**

- GitHub Actions から endpoint を poll して Issue を open/close する処理（PR-5）。
- alert threshold、連続失敗回数、通知抑制 policy の決定。
- `/health/ready` の意味変更、scheduler/取引の停止、production credential の移動。
- raw log、command event payload、backup repository、password、filesystem path の公開。

## Decisions

### 1. Component-local availability を持つ versioned DTO

（ユーザー確認済み）response は `schemaVersion`、`observedAt`、`revision` と、`daemon`、`providers`、`reconciler`、`gaps`、`backupRestore` を返す。各 component は `state` (`AVAILABLE` / `UNKNOWN`) と optional な `reason` を持つ。値を正常と解釈できる場合だけ `AVAILABLE` にし、source 不在、query failure、上限超過、schema mismatch、malformed value は stable reason code を伴う `UNKNOWN` にする。

`daemon` は enabled、cadence、in-process worker の最後の tick 時刻/outcome、最後の invocation terminal 時刻/semantic を返す。`providers` は固定 30 分 window の provider 別 total/failure/auth-failure 件数を返す。`reconciler` は last maintenance/transport と market-data state を返す。`gaps` は unresolved market-data/infrastructure gap の件数と oldest opened-at を返す。`backupRestore` は projection の freshness、service invocation terminal、backup/restore の last attempt/success を返す。

Endpoint 自体は source の一部が失敗しても HTTP 200 を返し、その component だけを `UNKNOWN` にする。route dependency の構築不能など request contract を生成できない failure だけを通常の server error とする。`/health/ready` はこの aggregation を呼ばない。

### 2. Dedicated bounded repository で DB snapshot を読む

（agent 仮決め）monitoring 用 repository は request の `observedAt` から固定した 30 分 window を使い、次の有限 query を実行する。

- daemon invocation terminal は対象 event type の最新 1 件。worker liveness は DB event から推測せず、scheduler tick ごとに更新する in-process status provider から読む。
- provider outcome は window 内の `RUNNER_PHASE_COMPLETED` のうち provider invocation phase だけを対象とする。deterministic phase は provider field がない正常 event として集計対象外にする。既存 audit schema に合わせ、`authFailureSuspected` は absent を false、文字列 `"true"` を true と解釈する。対象 event の未知 provider、malformed JSON、定義外 status は provider component 全体を `UNKNOWN` にし、部分集計を返さない。
- unresolved market-data gap と infrastructure gap は count と oldest opened-at の aggregate だけを返す。

query は既存 index、固定 row limit、transaction-local statement timeout を利用し、raw payload や row list を application DTO に渡さない。infrastructure gap は有限件の新しい boundary event から OPEN/CLOSE を再構成し、limit に到達して全履歴を証明できない場合は `UNKNOWN` にする。query ごとの例外/timeout は component-local `UNKNOWN` へ変換し、他 component を巻き込まない。件数には明示した上限を設け、上限到達時は切り捨てず `UNKNOWN` とする。

### 3. Reconciler は既存 status provider を正本とする

（agent 仮決め）reconciler freshness と current market-data state は既存 `ReconcilerStatusProvider` から読む。DB gap aggregate は unresolved 件数・開始時刻の裏付けとして別 component に置き、in-memory status と無理に統合しない。どちらか一方が欠損しても他方を推測で補完しない。

### 4. Root-only status から separate projection を生成する

（agent 仮決め）authoritative `/srv/fukurou/monitoring/backup-status.json` とその directory permission は変更せず、application へ直接 mount しない。新しい root-owned publisher が authoritative schema を厳密検証し、allowlist 済み field と systemd invocation lifecycle だけを separate public projection へ atomic rename で書く。

publisher は systemd unit の `ExecStartPre` で `RUNNING` invocation を先に記録し、`ExecStopPost` で `SERVICE_RESULT`、`EXIT_CODE`、`EXIT_STATUS` に基づく terminal を必ず記録する。backup/restore script が status を publish する前に SIGKILL/OOM で終了しても `ExecStopPost` が failure terminal を残す。boot/invocation identity と開始時刻が一致しない authoritative result は current invocation の success として採用しない。

projection は repository/password/command output/host path を schema 上受理しない。public directory は root だけが書き込み、application UID は file を read できる。application reader は regular file、size limit、schema version、field allowlist、timestamp/status enum を検証し、symlink、oversize、unknown field、malformed JSON を `UNKNOWN` にする。

### 5. Production mount は fixed public directory を default-off で作る

（反証反映済み agent 決定）`docker-compose.prod.yml` は固定 host path `/srv/fukurou/monitoring-public` を container 内の固定 directory へ read-only bind mount し、application はその中の固定 filename だけを読む。Compose の long syntax `bind.create_host_path: true` を明示し、root artifact 未導入時は空 directory が作られて component が `UNKNOWN` になる。任意 host path を受け取る環境変数は設けない。

single-file bind mount は atomic rename 後も旧 inode を保持するため採用しない。directory mount により publisher の atomic rename 後の新 inode を container から参照できる。public directory は projection 以外を格納しない installer invariant とし、authoritative directory と secret directory は compose source として構造的に参照できない。

### 6. Public response は必要最小限の allowlist とする

（ユーザー確認済み）route は revision、cadence、terminal semantic/timestamp、provider 集計、reconciler timestamp/state、gap aggregate、backup/restore timestamp/state だけを DTO 化する。exception message、SQL、raw JSON、provider output、invocation ID、filesystem path、repository、credential は response と log に出さない。

route-local `.describe {}` を wire contract の正本とし、schema example と reason enum を OpenAPI に反映する。

## Risks / Trade-offs

- [DB JSON extraction が malformed row で集計全体を失敗させる] → validation predicate と malformed count を同じ query contract に含め、部分値を返さず `UNKNOWN` にする。
- [空 public directory により root artifact 未導入が silent になる] → file absence を `UNKNOWN` reason `BACKUP_PROJECTION_NOT_ACTIVATED` とし、deploy doc と selftest で activation check を明示する。
- [systemd `ExecStartPre` / `ExecStopPost` 自体が実行不能になる] → stale `RUNNING` に加え、terminal projection も 36 時間更新されなければ endpoint が `UNKNOWN` にする。publisher は unit sandbox の write allowlist 内だけを更新する。
- [public projection permission が広すぎる] → directory/file owner と mode を installer selftest で固定し、secret field absence と symlink rejection を test する。
- [複数 source の時刻が完全一致しない] → request の `observedAt` と各 source の observed/published timestamp を返し、単一の偽の transactional snapshot として扱わない。

## Migration Plan

1. application endpoint と fixed public directory mount を deploy する。root artifact 未導入なら Compose が空 directory を作り、backup component は `UNKNOWN` である。
2. NAS で更新済み root artifact installer/selftest を実行し、publisher、schema、systemd hooks、public projection permission を確認する。
3. backup/restore service を一度実行または publisher probe で projection を生成する。
4. `GET /ops/monitoring` の backup component と全 redaction contract を確認する。

問題があれば public projection を退避して file absence の `UNKNOWN` へ戻す。authoritative backup status と backup/restore execution はこの切り替えから独立している。

## Open Questions

- なし。alert threshold と GitHub Issue lifecycle は PR-5 の OpenSpec で決定する。
