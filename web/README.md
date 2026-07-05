# Fukurou Web

Vite + React + TypeScript のローカル Web foundation です。

## Commands

- `npm run dev`
- `npm run generate:api`
- `npm run verify`

`npm run verify` は `typecheck` / `lint` / `test` / `build` / API 型再生成 / 生成型の差分確認をまとめて実行します。

## API Types

`src/api/openapi-types.ts` は `openapi/fukurou.openapi.json` から生成します。schema snapshot は Ktor の `/openapi.json` から取得したものを commit します。

Ktor API contract を変更した場合は、Ktor を起動して snapshot と生成型を更新します。

```bash
curl -fsS http://localhost:8080/openapi.json -o web/openapi/fukurou.openapi.json
npm --prefix web run generate:api
```

`npm run verify` は committed snapshot から `src/api/openapi-types.ts` を再生成し、生成型に差分が残っていないことを確認します。

## Local API Proxy

`npm run dev` では Vite dev server から Ktor へ同一 origin 相当で API を呼び出せます。既定の proxy 先は `http://localhost:8080` で、`VITE_FUKUROU_API_TARGET` で上書きできます。

Proxy 対象:

- `/evaluation`
- `/health`
- `/openapi.json`
- `/ops`
- `/revision`
