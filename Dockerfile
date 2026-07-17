# syntax=docker/dockerfile:1

# ---- build stage: fat JAR をコンテナ内でビルドする ----
# NAS 側には JDK / Gradle を入れず、このステージで完結させる。
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src

# ビルドに必要なファイルのみコピーする（.dockerignore で build 成果物等は除外済み）。
COPY gradlew ./
COPY gradle ./gradle
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY build-logic ./build-logic
COPY fukurou ./fukurou
COPY mcp-core ./mcp-core
COPY mcp ./mcp
COPY mcp-gmo-coin ./mcp-gmo-coin
COPY trading ./trading

# Gradle home を BuildKit のキャッシュmountに載せ、再ビルド時の依存DLを省く。
RUN --mount=type=cache,target=/root/.gradle \
    chmod +x gradlew && ./gradlew :fukurou:buildFatJar :mcp:buildFatJar :mcp-gmo-coin:buildFatJar --no-daemon

# ---- web build stage: Vite SPA を production asset としてビルドする ----
FROM node:22-bookworm-slim AS web-build
WORKDIR /src/web

COPY web/package.json web/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm \
    npm ci

COPY web ./
RUN npm run build

FROM debian:bookworm-slim AS launcher-build
RUN apt-get update && apt-get install -y --no-install-recommends gcc libc6-dev libssl-dev && rm -rf /var/lib/apt/lists/*
WORKDIR /src
COPY scripts/runtime/*.c ./
COPY scripts/runtime/*.h ./
RUN gcc -std=c17 -O2 -Wall -Wextra -Werror -o fukurou-llm-agent-launcher fukurou-llm-agent-launcher.c \
    && gcc -std=c17 -O2 -Wall -Wextra -Werror -o fukurou-mcp-launcher fukurou-mcp-launcher.c \
    && gcc -std=c17 -O2 -Wall -Wextra -Werror -o fukurou-runtime-supervisor fukurou-runtime-supervisor.c -lcrypto

# ---- runtime stage: 実行は軽量 JRE のみ ----
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

# 非 root 実行ユーザを用意する。
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl git libcap2-bin nodejs npm util-linux \
    && npm install -g @anthropic-ai/claude-code@2.1.199 @openai/codex@0.142.5 \
    && npm cache clean --force \
    && rm -rf /var/lib/apt/lists/* \
    && find / -xdev -type f -perm /6000 -exec chmod a-s {} + \
    && groupadd --system --gid 10004 llm-runtime \
    && useradd --system --uid 10001 --gid 10004 appuser \
    && useradd --system --uid 10002 --gid 10004 llm-agent \
    && groupadd --system --gid 10003 mcp-runtime \
    && useradd --system --uid 10003 --gid 10003 mcp-runtime \
    && install -d -o appuser -g llm-runtime -m 2750 /run/fukurou/llm-homes \
    && install -d -o appuser -g llm-runtime -m 0700 /run/fukurou/mcp-manifests \
    && install -d -o root -g root -m 0711 /run/fukurou/control \
    && install -d -o root -g root -m 0700 /var/lib/fukurou/launch-fence \
    && install -d -o root -g root -m 0700 /run/secrets

COPY --from=launcher-build --chown=root:root --chmod=4755 /src/fukurou-llm-agent-launcher /usr/local/libexec/fukurou-llm-agent-launcher
COPY --from=launcher-build --chown=root:root --chmod=4755 /src/fukurou-mcp-launcher /usr/local/libexec/fukurou-mcp-launcher
COPY --from=launcher-build --chown=root:root --chmod=0555 /src/fukurou-runtime-supervisor /usr/local/libexec/fukurou-runtime-supervisor
COPY --chown=root:root --chmod=0555 scripts/runtime/fukurou-mcp-canary-client.mjs /usr/local/libexec/fukurou-mcp-canary-client.mjs
COPY --chown=root:root --chmod=0555 scripts/runtime/validate-llm-launcher-probe.mjs /usr/local/libexec/validate-llm-launcher-probe.mjs

# Ktor fat JAR は fukurou/build/libs/<name>-all.jar に出力される。
COPY --from=build /src/fukurou/build/libs/*-all.jar app.jar
# MCP fat JAR は CLI の stdio 子プロセスとして利用する。
COPY --from=build /src/mcp/build/libs/fukurou-mcp-all.jar fukurou-mcp-all.jar
# standalone GMO Coin MCP fat JAR は分離検証や再利用用に同梱するが、entrypoint では起動しない。
COPY --from=build /src/mcp-gmo-coin/build/libs/gmo-coin-mcp-all.jar gmo-coin-mcp-all.jar
# one-shot runner が system prompt hash を計算するため、prompt 正本を同梱する。
COPY prompts ./prompts
# Vite SPA の production build output を Ktor から配信する。
COPY --from=web-build /src/web/dist ./web
# ARG は実際に参照する ENV の直前で宣言し、commit SHA だけが変わる build で
# 上記の重い RUN / COPY layer のキャッシュを壊さないようにする。
ARG FUKUROU_REVISION=unknown

# JVM をコンテナの memory limit に追従させる。
ENV FUKUROU_REVISION=$FUKUROU_REVISION
ENV FUKUROU_WEB_ROOT=/app/web
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Djava.io.tmpdir=/run/fukurou/llm-homes -Dfukurou.llm.cleanupQuarantinePath=/run/fukurou/llm-homes/.cleanup-quarantine"
EXPOSE 8080
ENTRYPOINT ["/usr/local/libexec/fukurou-runtime-supervisor"]
