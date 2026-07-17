## Context

`.github/workflows/deploy.yml` の単一 `build` job は対象 SHA を解決した直後に GHCR login と Docker build/push を行う。Dockerfile の fat-jar build は JVM test と detekt を品質ゲートとして実行しないため、Issue #190 の「test + detekt が deploy の必須 gate」を満たさない。

workflow_dispatch は `image_sha` で過去の main commit を選べる。したがって quality job が workflow checkout の既定 SHA を検証するだけでは不十分であり、resolve、quality、build、deploy が同じ resolved SHA を共有する必要がある。

## Goals / Non-Goals

**Goals:**

- resolved target SHA の `make test` と `make detekt` を image publication 前に実行する。
- quality が失敗または別 SHA を検証した場合、GHCR login、image build/push、NAS deployを開始しない。
- push と workflow_dispatch の両方で同じ dependency contract を使う。
- GitHub-hosted quality実行だけを追加し、NAS root境界を変更しない。

**Non-Goals:**

- deploy SHA の単調性、authorized rollback、migration compatibility。
- post-deploy verification、root executor、signed bundle schemaの変更。
- backup、restore、monitoring、alert。
- branch protectionのrequired check設定変更。

## Decisions

### 1. resolved SHAを独立jobのoutputにする

（agent 仮決め）`resolve` job は既存の40桁SHA正規化、git object確認、`origin/main` ancestor確認を担当し、`deploy_sha`をjob outputにする。`quality`と`build`はそれぞれ `actions/checkout` の `ref` に同じ output を指定し、checkout後の `HEAD` 完全一致を検証する。

build job内にquality stepを足す案は採らない。jobの途中でqualityが失敗してもGHCR permissionとimage publication処理が同じ権限境界に残り、required dependencyがworkflow構造として見えないためである。

### 2. image publicationはquality jobへの明示的dependencyにする

（ユーザー確認済み）`build` は `needs: [resolve, quality]` を持ち、quality successなしでは開始できない。`deploy` は引き続き build artifactだけを入力にする。GHCR `packages: write` はbuild jobだけに残し、resolve/qualityは`contents: read`だけを持つ。

### 3. repository標準commandとclean-tree検査を組み合わせる

（agent 仮決め）quality jobはJava 21を設定し、worktree固有の`GRADLE_USER_HOME`で`make test`と`make detekt`を順に実行する。現行`make detekt`は`--auto-correct`を含むため、最後に`git diff --exit-code`を実行し、自動修正が必要なHEADを成功扱いしない。

`./gradlew detekt`へ置き換える案はrepositoryのdocumented commandと挙動が分岐するため採らない。Makefile自体のsemantics変更はこのstageのscope外とする。

### 4. file-level contract testでproduction wiringを固定する

（agent 仮決め）既存`ReleaseDeployFoundationContractTest`へ、resolve output、exact-ref checkout、quality commands、clean-tree検査、build dependency、permission分離を確認するtestを追加する。GitHub Actionsをlocalで完全実行する新規frameworkは導入しない。

## Risks / Trade-offs

- [qualityによりdeploy開始が遅くなる] → image publication前の必須保険として受容し、Gradle cacheを有効にする。
- [pushとmanual runが同じSHAを重複検証する] → correctnessを優先する。job-level concurrencyの再設計はSHA単調性stageと混ぜない。
- [`make detekt`がtracked fileを自動修正する] → `git diff --exit-code`でfail closedにする。
- [file-level testがGitHub式の全semanticsを再現しない] → production workflowのdependency/command/permission invariantを固定し、PR上のActions実行を最終証拠にする。

## Migration Plan

1. workflow contract testを追加して現行workflowで失敗を確認する。
2. resolve/quality/build/deployへjobを分割し、docsを現在仕様へ更新する。
3. targeted contract testとOpenSpec validation後、full test/detekt/buildを実行する。
4. PR merge後のmain pushでqualityが先に成功し、その後だけimage build/deployが開始することを確認する。
5. rollbackはworkflowとdocs/testを前版へ戻すだけで、NAS root artifactやDB stateを変更しない。

## Open Questions

なし。
