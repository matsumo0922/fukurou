#!/usr/bin/env node

import readline from "node:readline";

const nonce = process.env.FUKUROU_CLI_CANARY_NONCE;
if (!nonce || !/^[0-9a-f-]{16,64}$/i.test(nonce)) process.exit(2);

const commonTools = [
  "calc_indicator", "get_account_status", "get_balance", "get_candles", "get_open_orders",
  "get_orderbook", "get_positions", "get_symbol_rules", "get_ticker", "get_trade_intent", "get_trades",
  "knowledge_get_recent_lessons", "knowledge_search_similar_setups",
];
const manifestId = process.argv[2] ?? "";
const tools = manifestId.includes("-proposer-")
  ? [...commonTools, "submit_decision"]
  : manifestId.includes("-falsifier-")
    ? [...commonTools, "preview_order", "submit_falsification"]
    : [];
if (tools.length === 0) process.exit(2);
const toolDefinitions = tools.map((name) => ({
  name,
  description: "Data-free CLI compatibility probe.",
  inputSchema: { type: "object", additionalProperties: true },
  annotations: { readOnlyHint: true, destructiveHint: false, idempotentHint: true },
}));

function respond(id, result) {
  process.stdout.write(`${JSON.stringify({ jsonrpc: "2.0", id, result })}\n`);
}

function fail(id, code, message) {
  process.stdout.write(`${JSON.stringify({ jsonrpc: "2.0", id, error: { code, message } })}\n`);
}

readline.createInterface({ input: process.stdin }).on("line", (line) => {
  let request;
  try {
    request = JSON.parse(line);
  } catch {
    return;
  }
  if (request.id === undefined) return;
  switch (request.method) {
    case "initialize":
      respond(request.id, {
        protocolVersion: request.params?.protocolVersion ?? "2025-03-26",
        capabilities: { tools: {} },
        serverInfo: { name: "fukurou-cli-canary", version: "1" },
      });
      break;
    case "ping":
      respond(request.id, {});
      break;
    case "tools/list":
      respond(request.id, { tools: toolDefinitions });
      break;
    case "tools/call":
      if (!tools.includes(request.params?.name)) {
        fail(request.id, -32602, "Unknown fixture tool.");
      } else {
        respond(request.id, {
          content: [{ type: "text", text: nonce }],
          structuredContent: { nonce },
          isError: false,
        });
      }
      break;
    default:
      fail(request.id, -32601, "Unsupported fixture method.");
  }
});
