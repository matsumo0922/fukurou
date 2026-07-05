# Fukurou Web

Vite + React + TypeScript のローカル Web foundation です。

## Commands

- `npm run dev`
- `npm run build`
- `npm run typecheck`
- `npm run lint`
- `npm run test`
- `npm run generate:api`
- `npm run verify`

## API Types

`src/api/openapi-types.ts` は `openapi/fukurou.openapi.json` から生成します。schema snapshot は Ktor の `/openapi.json` から取得したものを置き、型生成結果も差分確認できるように commit します。

```bash
curl -fsS http://localhost:8080/openapi.json -o web/openapi/fukurou.openapi.json
npm --prefix web run generate:api
```
