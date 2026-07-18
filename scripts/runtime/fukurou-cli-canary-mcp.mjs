#!/usr/bin/env node

import readline from "node:readline";
import { appendFileSync } from "node:fs";

const commonTools = [
  "calc_indicator", "get_account_status", "get_balance", "get_candles", "get_open_orders",
  "get_orderbook", "get_positions", "get_symbol_rules", "get_ticker", "get_trade_intent", "get_trades",
  "knowledge_get_recent_lessons", "knowledge_search_similar_setups",
];
const manifestId = process.argv[2] ?? "";
const phase = manifestId.includes("-proposer-") ? "PROPOSER" : manifestId.includes("-falsifier-") ? "FALSIFIER" : "";
const tools = phase === "PROPOSER" ? [...commonTools, "submit_decision"]
  : phase === "FALSIFIER" ? [...commonTools, "preview_order", "submit_falsification"] : [];
const writeTools = new Set(["submit_decision", "submit_falsification"]);
const openWorldTools = new Set([
  "calc_indicator", "get_candles", "get_orderbook", "get_symbol_rules", "get_ticker", "get_trades",
]);
if (tools.length === 0) process.exit(2);
const recordPath = process.env.FUKUROU_CLI_CANARY_RECORD_PATH;
if (recordPath !== `/tmp/${manifestId}.calls`) process.exit(2);
const toolDefinitions = tools.map((name) => ({
  name,
  description: "Data-free CLI compatibility probe.",
  inputSchema: { type: "object", additionalProperties: true },
  annotations: { readOnlyHint: !writeTools.has(name), openWorldHint: openWorldTools.has(name) },
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
        appendFileSync(recordPath, `${phase}\t${request.params.name}\n`, { mode: 0o600 });
        respond(request.id, {
          content: [{ type: "text", text: "FUKUROU_CLI_CANARY_OK" }],
          structuredContent: { marker: "FUKUROU_CLI_CANARY_OK" },
          isError: false,
        });
      }
      break;
    default:
      fail(request.id, -32601, "Unsupported fixture method.");
  }
});
