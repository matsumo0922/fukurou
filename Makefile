#!/usr/bin/make -f

.PHONY: detekt run build test

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
