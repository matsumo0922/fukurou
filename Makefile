#!/usr/bin/make -f

.PHONY: detekt run build test dev-db dev-api dev-web

# 静的解析（テンプレ準拠: auto-correct + continue）
detekt:
	./gradlew detekt --auto-correct --continue

# Ktor サーバ起動
run:
	./gradlew :fukurou:run

# ビルド
build:
	./gradlew build

# テスト
test:
	./gradlew test

# ローカル開発用 PostgreSQL 起動
dev-db:
	./scripts/dev-db

# ローカル開発用 Ktor API 起動
dev-api:
	./scripts/dev-api

# ローカル開発用 Web UI 起動
dev-web:
	./scripts/dev-web
