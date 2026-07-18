import { spawn } from "node:child_process";
import { createInterface } from "node:readline";
import { createServer } from "node:http";
import { existsSync, readFileSync, readdirSync, writeFileSync } from "node:fs";

const [manifestId, phase, rroAction] = process.argv.slice(2);
const rroActions = ["NO_TRADE", "EXIT", "REDUCE", "ADJUST_PROTECTION", "ENTER", "ADD_LONG"];
if (!/^[0-9a-f]{48}$/.test(manifestId) || !["PROPOSER", "FALSIFIER", "RISK_REDUCTION_ONLY"].includes(phase)) {
  throw new Error("canonical manifest id and phase are required");
}
if (phase === "RISK_REDUCTION_ONLY" && !rroActions.includes(rroAction)) throw new Error("RRO action is required");
const providerHome = process.env.HOME;
const providerFiles = readdirSync(providerHome);
const authName = phase === "PROPOSER" ? ".credentials.json" : "auth.json";
const configName = phase === "PROPOSER"
  ? providerFiles.find(name => name.startsWith("claude-mcp-config"))
  : "config.toml";
if (!configName || !readFileSync(`${providerHome}/${authName}`, "utf8").includes("fixture-auth")) {
  throw new Error("provider fixture could not read per-run auth");
}
readFileSync(`${providerHome}/${configName}`, "utf8");
process.stdout.write(JSON.stringify({ event: "provider_fixture_read", phase, authName, configName }) + "\n");

const fixture = createServer((request, response) => {
  const name = request.url.split("/").pop().split("?")[0];
  const bodies = {
    ticker: { status: 0, data: [{ symbol: "BTC", ask: "101", bid: "99", high: "110", last: "100", low: "90", volume: "1.0", timestamp: "2026-07-01T00:00:00.000Z" }] },
    orderbooks: { status: 0, data: { asks: [{ price: "101", size: "0.1" }], bids: [{ price: "99", size: "0.1" }], symbol: "BTC" } },
    trades: { status: 0, data: { list: [{ price: "100", side: "BUY", size: "0.01", timestamp: "2026-07-01T00:00:00.000Z" }] } },
    symbols: { status: 0, data: [{ symbol: "BTC", minOrderSize: "0.0001", maxOrderSize: "5", sizeStep: "0.0001", tickSize: "1", takerFee: "0.0005", makerFee: "-0.0001" }] },
    klines: { status: 0, data: [{ openTime: "1751328000000", open: "100", high: "110", low: "90", close: "105", volume: "1.0" }] },
  };
  response.writeHead(bodies[name] ? 200 : 404, { "Content-Type": "application/json" });
  response.end(JSON.stringify(bodies[name] ?? { status: 1 }));
});
await new Promise(resolve => fixture.listen(18080, "127.0.0.1", resolve));

const child = spawn("/usr/local/libexec/fukurou-mcp-launcher", [manifestId], {
  stdio: ["pipe", "pipe", "pipe"],
});
child.stderr.pipe(process.stderr);
child.on("exit", (code, signal) => {
  process.stderr.write(`mcp child exited code=${code} signal=${signal ?? "none"}\n`);
});
writeFileSync(`${providerHome}/mcp-canary.pid`, `${child.pid}\n`, { mode: 0o660 });
const probeReleasePath = `${providerHome}/mcp-canary.probe-complete`;
if (phase === "PROPOSER") {
  for (let attempt = 0; !existsSync(probeReleasePath); attempt++) {
    if (attempt >= 1200) throw new Error("live MCP denial probe did not release canary client");
    await new Promise(resolve => setTimeout(resolve, 25));
  }
}
const status = readFileSync("/proc/self/status", "utf8");
const statusFields = Object.fromEntries(status.split("\n").filter(line => line.includes(":"))
  .map(line => [line.slice(0, line.indexOf(":")), line.slice(line.indexOf(":") + 1).trim()]));
process.stdout.write(JSON.stringify({
  event: "launcher_probe",
  uid: statusFields.Uid,
  gid: statusFields.Gid,
  groups: statusFields.Groups,
  capInh: statusFields.CapInh,
  capPrm: statusFields.CapPrm,
  capEff: statusFields.CapEff,
  capBnd: statusFields.CapBnd,
  capAmb: statusFields.CapAmb,
  noNewPrivs: statusFields.NoNewPrivs,
  dumpable: process.env.FUKUROU_CANARY_LLM_DUMPABLE,
  coreLimit: process.env.FUKUROU_CANARY_LLM_CORE_LIMIT,
  launchFds: process.env.FUKUROU_CANARY_LLM_LAUNCH_FDS,
  umask: process.umask().toString(8).padStart(4, "0"),
  liveFds: readdirSync("/proc/self/fd").sort((left, right) => Number(left) - Number(right)),
  env: Object.keys(process.env).sort(),
}) + "\n");
child.stdin.on("error", error => {
  process.stderr.write(`mcp stdin error code=${error.code ?? "unknown"}\n`);
});
const lines = createInterface({ input: child.stdout });
let nextId = 1;
const pending = new Map();
lines.on("line", line => {
  const message = JSON.parse(line);
  if (message.id != null && pending.has(message.id)) {
    pending.get(message.id)(message);
    pending.delete(message.id);
  }
});

function request(method, params = {}) {
  const id = nextId++;
  child.stdin.write(JSON.stringify({ jsonrpc: "2.0", id, method, params }) + "\n");
  return new Promise((resolve, reject) => {
    const timeout = setTimeout(() => reject(new Error(`timeout: ${method}`)), 15000);
    pending.set(id, message => {
      clearTimeout(timeout);
      if (message.error) reject(new Error(`${method}: ${message.error.message}`));
      else resolve(message.result);
    });
  });
}

async function call(name, args = {}) {
  const result = await request("tools/call", { name, arguments: args });
  if (result.isError) throw new Error(`tool failed: ${name}`);
  process.stdout.write(JSON.stringify({ event: "tool_completed", tool: name }) + "\n");
  return result;
}

await request("initialize", {
  protocolVersion: "2025-03-26",
  capabilities: {},
  clientInfo: { name: "fukurou-isolation-canary", version: "1" },
});
child.stdin.write(JSON.stringify({ jsonrpc: "2.0", method: "notifications/initialized", params: {} }) + "\n");

if (phase === "PROPOSER") {
  await call("get_ticker");
  await call("get_candles", { interval: "1hour", limit: 1 });
  await call("get_orderbook");
  await call("get_trades");
  await call("get_symbol_rules");
  await call("calc_indicator", { interval: "1hour", indicator: "SMA", params: { period: 1 } });
  await call("get_balance");
  await call("get_positions");
  await call("get_open_orders");
  await call("get_account_status");
  await call("knowledge_get_recent_lessons");
  await call("knowledge_search_similar_setups", { signal_summary: "breakout", limit: 3 });
  const decision = await call("submit_decision", {
    invocation_id: "mcp-canary-run", action: "ENTER", setup_tags: ["breakout"],
    estimated_win_probability: "0.73", expected_r_multiple: "1.80", round_trip_cost_r: "0.05",
    tool_evidence_ids: ["tool-1"], fact_check: "{\"ticker\":true}",
    self_review: "{\"reasonsNotToTrade\":[]}", reason_ja: "canary fixture",
    symbol: "BTC", side: "BUY", type: "MARKET", size_btc: "0.0050",
    protective_stop_price_jpy: "9700000", take_profit_price_jpy: "10500000",
    trade_plan_revision_count: 0, trade_plan_thesis_ja: "canary fixture",
    trade_plan_invalidation_conditions_ja: ["fixture"], trade_plan_target_price_jpy: "10500000",
    trade_plan_time_stop_at: "2026-07-02T01:00:00Z",
  });
  const intentId = decision.structuredContent.intent_id;
  await call("get_trade_intent", { intent_id: intentId });
  process.stdout.write(JSON.stringify({ event: "intent", intent_id: intentId }) + "\n");
} else if (phase === "FALSIFIER") {
  const intentId = process.env.FUKUROU_CANARY_INTENT_ID;
  if (!intentId) throw new Error("canary intent id is required");
  await call("preview_order", {
    intent_id: intentId, side: "BUY", type: "MARKET", size_btc: "0.0050",
    protective_stop_price_jpy: "9700000", take_profit_price_jpy: "10500000",
    estimated_win_probability: "0.73", reason: "canary fixture",
  });
  await call("submit_falsification", {
    intent_id: intentId, verdict: "APPROVED", llm_provider: "codex", reason_ja: "canary fixture",
  });
} else {
  const invocationId = process.env.FUKUROU_INVOCATION_ID;
  if (!invocationId) throw new Error("canary invocation id is required");
  const decision = {
    invocation_id: invocationId, action: rroAction,
    setup_tags: ["canary"], estimated_win_probability: "0", expected_r_multiple: "0",
    fact_check: "{}", self_review: "{}", reason_ja: "canary fixture",
  };
  if (rroAction === "REDUCE") decision.close_ratio = "0.5";
  if (["ENTER", "ADD_LONG"].includes(rroAction)) {
    Object.assign(decision, {
      symbol: "BTC", side: "BUY", type: "MARKET", size_btc: "0.0050",
      protective_stop_price_jpy: "9700000", take_profit_price_jpy: "10500000",
      trade_plan_revision_count: 0, trade_plan_thesis_ja: "canary fixture",
      trade_plan_invalidation_conditions_ja: ["fixture"],
      trade_plan_invalidation_predicates: [{ type: "LAST_PRICE_AT_OR_BELOW", threshold_jpy: "9700000" }],
      trade_plan_target_price_jpy: "10500000",
    });
  }
  const shouldAccept = ["NO_TRADE", "EXIT", "REDUCE", "ADJUST_PROTECTION"].includes(rroAction);
  let accepted = false;
  try {
    await call("submit_decision", decision);
    accepted = true;
  } catch (error) {
    if (shouldAccept) throw error;
  }
  if (accepted !== shouldAccept) throw new Error(`RRO action matrix mismatch for ${rroAction}`);
  if (accepted) {
    process.stdout.write(JSON.stringify({ event: "rro_action_accepted", action: rroAction }) + "\n");
  } else {
    process.stdout.write(JSON.stringify({ event: "rro_action_rejected", action: rroAction }) + "\n");
  }
}

child.stdin.end();
await new Promise((resolve, reject) => {
  const timeout = setTimeout(() => reject(new Error("MCP child did not exit after stdio EOF")), 5000);
  child.once("exit", () => {
    clearTimeout(timeout);
    resolve();
  });
});
fixture.close();
