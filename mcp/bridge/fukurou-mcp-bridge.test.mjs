import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { chmod, mkdir, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import test from "node:test";
import process from "node:process";

const TEST_DIR = dirname(fileURLToPath(import.meta.url));
const BRIDGE_PATH = resolve(TEST_DIR, "fukurou-mcp-bridge.mjs");
const FAKE_BACKEND_PATH = resolve(TEST_DIR, "test/fake-backend.mjs");
const RESPONSE_TIMEOUT_MS = 2_000;

test("initialize and tools/list return snapshot results with client ids before backend is ready", async () => {
  const context = await createBridgeContext({
    backendEnv: {
      FAKE_BACKEND_INIT_DELAY_MS: "300",
      FAKE_BACKEND_TOOLS: "snapshot_tool",
    },
  });

  try {
    const bridge = context.spawnBridge();
    bridge.send({
      jsonrpc: "2.0",
      id: 101,
      method: "initialize",
      params: {
        protocolVersion: "2024-11-05",
        capabilities: {},
        clientInfo: {
          name: "test-client",
          version: "1.0.0",
        },
      },
    });
    bridge.send({
      jsonrpc: "2.0",
      id: "list-before-ready",
      method: "tools/list",
      params: {},
    });

    const initializeResponse = await bridge.readJson();
    const toolsListResponse = await bridge.readJson();

    assert.equal(initializeResponse.id, 101);
    assert.equal(initializeResponse.result.serverInfo.name, "snapshot-fukurou");
    assert.equal(toolsListResponse.id, "list-before-ready");
    assert.deepEqual(
      toolsListResponse.result.tools.map((tool) => tool.name),
      ["snapshot_tool"],
    );

    await waitForCondition(() => context.readEvents().length > 0);
    await bridge.close();
    const events = context.readEvents();
    assert.equal(events[0].method, "initialize");
    assert.match(events[0].id, /^bridge:/);
    assert.equal(events[0].params.clientInfo.name, "test-client");
  } finally {
    await context.cleanup();
  }
});

test("tools/list snapshot response is filtered by FUKUROU_MCP_ALLOWED_TOOLS", async () => {
  const context = await createBridgeContext({
    snapshotTools: ["read_tool", "trade_tool", "submit_decision"],
    backendEnv: {
      FAKE_BACKEND_INIT_DELAY_MS: "100",
      FAKE_BACKEND_TOOLS: "read_tool,trade_tool,submit_decision",
    },
  });

  try {
    const bridge = context.spawnBridge({
      env: {
        FUKUROU_MCP_ALLOWED_TOOLS: "read_tool,submit_decision",
      },
    });
    bridge.send(initializeRequest("init"));
    bridge.send({
      jsonrpc: "2.0",
      id: "list-filtered",
      method: "tools/list",
      params: {},
    });

    await bridge.readJson();
    const toolsListResponse = await bridge.readJson();

    assert.deepEqual(
      toolsListResponse.result.tools.map((tool) => tool.name),
      ["read_tool", "submit_decision"],
    );
    await waitForBackendReady(context);
    await new Promise((resolve) => {
      setTimeout(resolve, 50);
    });
    assert.doesNotMatch(bridge.stderrText(), /tools\/list snapshot mismatch/);

    await bridge.close();
  } finally {
    await context.cleanup();
  }
});

test("tools/list after backend readiness still returns filtered snapshot", async () => {
  const context = await createBridgeContext({
    snapshotTools: ["read_tool", "trade_tool", "submit_decision"],
    backendEnv: {
      FAKE_BACKEND_TOOLS: "read_tool,trade_tool,submit_decision",
    },
  });

  try {
    const bridge = context.spawnBridge({
      env: {
        FUKUROU_MCP_ALLOWED_TOOLS: "read_tool,submit_decision",
      },
    });
    bridge.send(initializeRequest("init"));
    await bridge.readJson();
    await waitForBackendReady(context);
    await waitForCondition(() => context.readEvents().filter((event) => event.method === "tools/list").length === 1);

    bridge.send({
      jsonrpc: "2.0",
      id: "list-after-ready-filtered",
      method: "tools/list",
      params: {},
    });

    const response = await bridge.readJson();
    assert.deepEqual(
      response.result.tools.map((tool) => tool.name),
      ["read_tool", "submit_decision"],
    );
    await new Promise((resolve) => {
      setTimeout(resolve, 50);
    });
    assert.equal(context.readEvents().filter((event) => event.method === "tools/list").length, 1);
    assert.doesNotMatch(bridge.stderrText(), /tools\/list snapshot mismatch/);

    await bridge.close();
  } finally {
    await context.cleanup();
  }
});

test("tools/call requests wait for backend readiness and flush in order", async () => {
  const context = await createBridgeContext({
    backendEnv: {
      FAKE_BACKEND_INIT_DELAY_MS: "100",
      FAKE_BACKEND_TOOLS: "snapshot_tool",
    },
  });

  try {
    const bridge = context.spawnBridge();
    bridge.send(initializeRequest("init"));
    bridge.send({
      jsonrpc: "2.0",
      id: "call-1",
      method: "tools/call",
      params: {
        name: "snapshot_tool",
        arguments: {},
      },
    });

    await bridge.readJson();
    const callResponse = await bridge.readJson();

    assert.equal(callResponse.id, "call-1");
    assert.equal(callResponse.result.structuredContent.name, "snapshot_tool");

    await bridge.close();
    const methods = context.readEvents().map((event) => event.method);
    assert.deepEqual(methods.slice(0, 3), [
      "initialize",
      "notifications/initialized",
      "tools/call",
    ]);
  } finally {
    await context.cleanup();
  }
});

test("ready backend forwards client and backend messages transparently", async () => {
  const context = await createBridgeContext({
    backendEnv: {
      FAKE_BACKEND_TOOLS: "snapshot_tool",
    },
  });

  try {
    const bridge = context.spawnBridge();
    bridge.send(initializeRequest("init"));
    await bridge.readJson();
    await waitForBackendReady(context);

    bridge.send({
      jsonrpc: "2.0",
      id: "custom-1",
      method: "custom/echo",
      params: {
        ok: true,
      },
    });

    const response = await bridge.readJson();
    assert.equal(response.id, "custom-1");
    assert.equal(response.result.method, "custom/echo");
    assert.deepEqual(response.result.params, { ok: true });

    await bridge.close();
  } finally {
    await context.cleanup();
  }
});

test("tools/list after backend readiness returns snapshot instead of backend list", async () => {
  const context = await createBridgeContext({
    snapshotTools: ["snapshot_tool"],
    backendEnv: {
      FAKE_BACKEND_TOOLS: "backend_tool",
    },
  });

  try {
    const bridge = context.spawnBridge();
    bridge.send(initializeRequest("init"));
    await bridge.readJson();
    await waitForBackendReady(context);

    bridge.send({
      jsonrpc: "2.0",
      id: "list-after-ready",
      method: "tools/list",
      params: {},
    });

    const response = await bridge.readJson();
    assert.equal(response.id, "list-after-ready");
    assert.deepEqual(
      response.result.tools.map((tool) => tool.name),
      ["snapshot_tool"],
    );

    await bridge.close();
  } finally {
    await context.cleanup();
  }
});

test("ping responds immediately and does not wait behind queued tool calls", async () => {
  const context = await createBridgeContext({
    backendEnv: {
      FAKE_BACKEND_INIT_DELAY_MS: "300",
      FAKE_BACKEND_TOOLS: "snapshot_tool",
    },
  });

  try {
    const bridge = context.spawnBridge();
    bridge.send(initializeRequest("init"));
    bridge.send({
      jsonrpc: "2.0",
      id: "queued-call",
      method: "tools/call",
      params: {
        name: "snapshot_tool",
        arguments: {},
      },
    });
    bridge.send({
      jsonrpc: "2.0",
      id: "ping-1",
      method: "ping",
      params: {},
    });

    await bridge.readJson();
    const pingResponse = await bridge.readJson();

    assert.equal(pingResponse.id, "ping-1");
    assert.deepEqual(pingResponse.result, {});

    await bridge.close();
  } finally {
    await context.cleanup();
  }
});

test("backend stdout non-json lines go to bridge stderr without polluting client stdout", async () => {
  const context = await createBridgeContext({
    backendEnv: {
      FAKE_BACKEND_NON_JSON_STDOUT: "true",
      FAKE_BACKEND_TOOLS: "snapshot_tool",
    },
  });

  try {
    const bridge = context.spawnBridge();
    bridge.send(initializeRequest("init"));

    const response = await bridge.readJson();
    assert.equal(response.id, "init");
    await waitForCondition(() => bridge.stderrText().includes("backend stdout emitted non-json line"));

    await bridge.close();
  } finally {
    await context.cleanup();
  }
});

test("backend stderr is forwarded without redacting the diagnostic line", async () => {
  const context = await createBridgeContext({
    backendEnv: {
      FAKE_BACKEND_STDERR_LINE: "backend startup diagnostic",
      FAKE_BACKEND_TOOLS: "snapshot_tool",
    },
  });

  try {
    const bridge = context.spawnBridge();
    bridge.send(initializeRequest("init"));

    await bridge.readJson();
    await waitForCondition(() => bridge.stderrText().includes("backend startup diagnostic"));
    assert.doesNotMatch(bridge.stderrText(), /backend stderr line received/);

    await bridge.close();
  } finally {
    await context.cleanup();
  }
});

test("tools/list comparison warns with tool names only when snapshot drifts", async () => {
  const context = await createBridgeContext({
    snapshotTools: ["snapshot_only"],
    backendEnv: {
      FAKE_BACKEND_TOOLS: "backend_only",
    },
  });

  try {
    const bridge = context.spawnBridge();
    bridge.send(initializeRequest("init"));
    await bridge.readJson();

    await waitForCondition(() => bridge.stderrText().includes("snapshot_only") &&
      bridge.stderrText().includes("backend_only"));

    assert.match(bridge.stderrText(), /tools\/list snapshot mismatch/);
    assert.doesNotMatch(bridge.stderrText(), /inputSchema|description|properties/);

    await bridge.close();
  } finally {
    await context.cleanup();
  }
});

test("filtered zero-tool snapshot exits non-zero", async () => {
  const context = await createBridgeContext({
    snapshotTools: ["trade_tool"],
    backendEnv: {
      FAKE_BACKEND_TOOLS: "trade_tool",
    },
  });

  try {
    const bridge = context.spawnBridge({
      env: {
        FUKUROU_MCP_ALLOWED_TOOLS: "get_ticker",
      },
    });
    const exitStatus = await bridge.waitForExit();

    assert.notEqual(exitStatus.code, 0);
    assert.match(bridge.stderrText(), /filtered MCP tools snapshot contained zero tools/);
    assert.equal(context.readEvents().length, 0);
  } finally {
    await context.cleanup();
  }
});

test("backend spawn failure exits non-zero", async () => {
  const context = await createBridgeContext({
    createJavaWrapper: false,
  });

  try {
    const bridge = context.spawnBridge({
      env: {
        PATH: context.binDir,
      },
    });
    const exitStatus = await bridge.waitForExit();

    assert.notEqual(exitStatus.code, 0);
    assert.match(bridge.stderrText(), /backend spawn failed/);
  } finally {
    await context.cleanup();
  }
});

test("backend exit before ready exits non-zero", async () => {
  const context = await createBridgeContext({
    backendEnv: {
      FAKE_BACKEND_EXIT_EARLY: "true",
    },
  });

  try {
    const bridge = context.spawnBridge();
    const exitStatus = await bridge.waitForExit();

    assert.notEqual(exitStatus.code, 0);
    assert.match(bridge.stderrText(), /backend exited before ready/);
  } finally {
    await context.cleanup();
  }
});

test("backend exit after ready exits non-zero", async () => {
  const context = await createBridgeContext({
    backendEnv: {
      FAKE_BACKEND_EXIT_AFTER_INITIALIZED: "true",
    },
  });

  try {
    const bridge = context.spawnBridge();
    bridge.send(initializeRequest("init"));
    await bridge.readJson();
    const exitStatus = await bridge.waitForExit();

    assert.notEqual(exitStatus.code, 0);
    assert.match(bridge.stderrText(), /backend exited after ready/);
  } finally {
    await context.cleanup();
  }
});

test("stdin EOF closes backend and exits with backend status", async () => {
  const context = await createBridgeContext();

  try {
    const bridge = context.spawnBridge();
    const exitStatus = await bridge.close();

    assert.equal(exitStatus.code, 0);
  } finally {
    await context.cleanup();
  }
});

test("SIGTERM closes backend and exits without waiting for stdin EOF", async () => {
  const context = await createBridgeContext();

  try {
    const bridge = context.spawnBridge();
    bridge.send(initializeRequest("init"));
    await bridge.readJson();
    await waitForBackendReady(context);

    bridge.child.kill("SIGTERM");
    const exitStatus = await bridge.waitForExit();

    assert.equal(exitStatus.code, 0);
  } finally {
    await context.cleanup();
  }
});

test("chunk-split client JSON lines are reassembled", async () => {
  const context = await createBridgeContext();

  try {
    const bridge = context.spawnBridge();
    const request = JSON.stringify(initializeRequest("chunked"));
    bridge.writeChunk(request.slice(0, 12));
    bridge.writeChunk(request.slice(12));
    bridge.writeChunk("\n");

    const response = await bridge.readJson();
    assert.equal(response.id, "chunked");
    assert.equal(response.result.serverInfo.name, "snapshot-fukurou");

    await bridge.close();
  } finally {
    await context.cleanup();
  }
});

test("generate snapshot mode writes initialize and tools/list results from backend", async () => {
  const context = await createBridgeContext({
    backendEnv: {
      FAKE_BACKEND_TOOLS: "generated_a,generated_b",
    },
  });

  try {
    const outputSnapshotPath = join(context.tmpDir, "generated-snapshot.json");
    const bridge = spawn(process.execPath, [
      BRIDGE_PATH,
      "--generate-snapshot",
      context.jarPath,
      "--snapshot",
      outputSnapshotPath,
      "--ready-timeout-ms",
      "1000",
      "--shutdown-timeout-ms",
      "500",
    ], {
      env: context.bridgeEnv(),
      stdio: ["ignore", "pipe", "pipe"],
    });
    const exitStatus = await waitForExit(bridge);

    assert.equal(exitStatus.code, 0);
    const snapshot = JSON.parse(readFileSync(outputSnapshotPath, "utf8"));
    assert.equal(snapshot.initializeResult.serverInfo.name, "fake-fukurou-backend");
    assert.deepEqual(
      snapshot.toolsListResult.tools.map((tool) => tool.name),
      ["generated_a", "generated_b"],
    );

    const events = context.readEvents();
    assert.equal(events[0].method, "initialize");
    assert.equal(events[1].method, "notifications/initialized");
    assert.equal(events[2].method, "tools/list");
  } finally {
    await context.cleanup();
  }
});

async function createBridgeContext({
  snapshotTools = ["snapshot_tool"],
  backendEnv = {},
  createJavaWrapper = true,
} = {}) {
  const tmpDir = mkdtempSync(join(tmpdir(), "fukurou-bridge-test-"));
  const binDir = join(tmpDir, "bin");
  const snapshotPath = join(tmpDir, "snapshot.json");
  const eventsFile = join(tmpDir, "backend-events.jsonl");
  const jarPath = join(tmpDir, "fake-backend.jar");
  await mkdir(binDir, { recursive: true });
  await writeFile(jarPath, "not a real jar\n");
  writeSnapshot(snapshotPath, snapshotTools);

  if (createJavaWrapper) {
    const javaPath = join(binDir, "java");
    await writeFile(
      javaPath,
      `#!/bin/sh\nexec ${JSON.stringify(process.execPath)} ${JSON.stringify(FAKE_BACKEND_PATH)} "$@"\n`,
    );
    await chmod(javaPath, 0o755);
  }

  return {
    tmpDir,
    binDir,
    snapshotPath,
    eventsFile,
    jarPath,
    bridgeEnv(extraEnv = {}) {
      return {
        ...process.env,
        ...backendEnv,
        PATH: `${binDir}:${process.env.PATH ?? ""}`,
        FAKE_BACKEND_EVENTS_FILE: eventsFile,
        ...extraEnv,
      };
    },
    spawnBridge({ env = {} } = {}) {
      return new BridgeProcess(spawn(process.execPath, [
        BRIDGE_PATH,
        "--snapshot",
        snapshotPath,
        "--ready-timeout-ms",
        "1000",
        "--shutdown-timeout-ms",
        "500",
        jarPath,
      ], {
        env: this.bridgeEnv(env),
        stdio: ["pipe", "pipe", "pipe"],
      }));
    },
    readEvents() {
      try {
        return readFileSync(eventsFile, "utf8")
          .split("\n")
          .filter((line) => line.length > 0)
          .map((line) => JSON.parse(line));
      } catch (error) {
        if (error.code === "ENOENT") return [];

        throw error;
      }
    },
    async cleanup() {
      await rm(tmpDir, { recursive: true, force: true });
    },
  };
}

class BridgeProcess {
  constructor(child) {
    this.child = child;
    this.stdoutBuffer = "";
    this.stderrBuffer = "";
    this.stdoutWaiters = [];
    child.stdout.setEncoding("utf8");
    child.stderr.setEncoding("utf8");
    child.stdout.on("data", (chunk) => {
      this.stdoutBuffer += chunk;
      this.resolveStdoutWaiters();
    });
    child.stderr.on("data", (chunk) => {
      this.stderrBuffer += chunk;
    });
    this.exitPromise = waitForExit(child);
  }

  send(message) {
    this.writeChunk(`${JSON.stringify(message)}\n`);
  }

  writeChunk(chunk) {
    this.child.stdin.write(chunk);
  }

  async readJson() {
    const line = await this.readLine();

    return JSON.parse(line);
  }

  async readLine() {
    const existingLine = this.shiftLine();
    if (existingLine !== null) return existingLine;

    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        reject(new Error(`timed out waiting for bridge stdout; stderr=${this.stderrBuffer}`));
      }, RESPONSE_TIMEOUT_MS);
      this.stdoutWaiters.push({
        resolve: (line) => {
          clearTimeout(timer);
          resolve(line);
        },
        reject,
      });
    });
  }

  resolveStdoutWaiters() {
    while (this.stdoutWaiters.length > 0) {
      const line = this.shiftLine();
      if (line === null) return;

      const waiter = this.stdoutWaiters.shift();
      waiter.resolve(line);
    }
  }

  shiftLine() {
    const lineBreakIndex = this.stdoutBuffer.indexOf("\n");
    if (lineBreakIndex < 0) return null;

    const line = this.stdoutBuffer.slice(0, lineBreakIndex);
    this.stdoutBuffer = this.stdoutBuffer.slice(lineBreakIndex + 1);
    return line;
  }

  stderrText() {
    return this.stderrBuffer;
  }

  async close() {
    this.child.stdin.end();

    return this.waitForExit();
  }

  async waitForExit() {
    return this.exitPromise;
  }
}

function initializeRequest(id) {
  return {
    jsonrpc: "2.0",
    id,
    method: "initialize",
    params: {
      protocolVersion: "2024-11-05",
      capabilities: {},
      clientInfo: {
        name: "test-client",
        version: "1.0.0",
      },
    },
  };
}

function writeSnapshot(snapshotPath, toolNames) {
  writeFileSync(
    snapshotPath,
    `${JSON.stringify({
      schemaVersion: 1,
      generatedAt: "2026-07-09T00:00:00.000Z",
      initializeResult: {
        protocolVersion: "2024-11-05",
        capabilities: {
          tools: {},
        },
        serverInfo: {
          name: "snapshot-fukurou",
          version: "0.1.0",
        },
      },
      toolsListResult: {
        tools: toolNames.map((toolName) => ({
          name: toolName,
          description: `${toolName} description`,
          inputSchema: {
            type: "object",
            properties: {},
          },
        })),
      },
    })}\n`,
  );
}

async function waitForBackendReady(context) {
  await waitForCondition(() => context.readEvents().some((event) => event.method === "notifications/initialized"));
}

async function waitForCondition(predicate) {
  const startedAt = Date.now();

  while (Date.now() - startedAt < RESPONSE_TIMEOUT_MS) {
    if (predicate()) return;

    await new Promise((resolve) => {
      setTimeout(resolve, 10);
    });
  }

  throw new Error("condition timed out");
}

function waitForExit(child) {
  return new Promise((resolve) => {
    child.once("exit", (code, signal) => resolve({ code, signal }));
  });
}
