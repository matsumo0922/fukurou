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
COPY mcp ./mcp
COPY mcp-gmo-coin ./mcp-gmo-coin
COPY trading ./trading

# Gradle home を BuildKit のキャッシュmountに載せ、再ビルド時の依存DLを省く。
RUN --mount=type=cache,target=/root/.gradle \
    chmod +x gradlew && ./gradlew :fukurou:buildFatJar :mcp:buildFatJar :mcp-gmo-coin:buildFatJar --no-daemon

# ---- runtime stage: 実行は軽量 JRE のみ ----
FROM eclipse-temurin:21-jre AS runtime
ARG FUKUROU_REVISION=unknown
WORKDIR /app

# 非 root 実行ユーザを用意する。
RUN useradd --system --uid 10001 appuser

# Ktor fat JAR は fukurou/build/libs/<name>-all.jar に出力される。
COPY --from=build /src/fukurou/build/libs/*-all.jar app.jar
# MCP fat JAR は CLI の stdio 子プロセスとして利用する。
COPY --from=build /src/mcp/build/libs/fukurou-mcp-all.jar fukurou-mcp-all.jar
# standalone GMO Coin MCP fat JAR は分離検証や再利用用に同梱するが、entrypoint では起動しない。
COPY --from=build /src/mcp-gmo-coin/build/libs/gmo-coin-mcp-all.jar gmo-coin-mcp-all.jar
USER appuser

# JVM をコンテナの memory limit に追従させる。
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENV FUKUROU_REVISION=$FUKUROU_REVISION
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
