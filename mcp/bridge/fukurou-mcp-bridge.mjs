#!/usr/bin/env node
import { spawn } from "node:child_process";
import { readFileSync, writeFileSync } from "node:fs";
import process from "node:process";

const DEFAULT_SNAPSHOT_PATH = "/app/fukurou-mcp-tools.json";
const DEFAULT_READY_TIMEOUT_MS = 30_000;
const DEFAULT_SHUTDOWN_TIMEOUT_MS = 3_000;
const JSON_RPC_VERSION = "2.0";
const INITIALIZE_METHOD = "initialize";
const INITIALIZED_NOTIFICATION_METHOD = "notifications/initialized";
const TOOLS_LIST_METHOD = "tools/list";
const PING_METHOD = "ping";
const ALLOWED_TOOLS_ENV = "FUKUROU_MCP_ALLOWED_TOOLS";
const TEST_RUNTIME_ENV = "FUKUROU_MCP_TEST_IN_MEMORY_RUNTIME";
const TEST_RUNTIME_PROPERTY = "-Dfukurou.mcp.testInMemoryRuntime=true";

async function main() {
  const options = parseArgs(process.argv.slice(2));

  if (options.generateSnapshot) {
    await generateSnapshot(options);
    return;
  }

  const snapshot = loadSnapshot(options.snapshotPath);
  const bridge = new FukurouMcpBridge(options, snapshot);
  bridge.start();
}

function parseArgs(args) {
  const options = {
    snapshotPath: DEFAULT_SNAPSHOT_PATH,
    readyTimeoutMs: DEFAULT_READY_TIMEOUT_MS,
    shutdownTimeoutMs: DEFAULT_SHUTDOWN_TIMEOUT_MS,
    generateSnapshot: false,
    jarPath: null,
  };
  const positional = [];

  for (let index = 0; index < args.length; index += 1) {
    const argument = args[index];

    if (argument === "--") {
      positional.push(...args.slice(index + 1));
      break;
    }

    if (argument === "--snapshot") {
      options.snapshotPath = requireNextArg(args, index, argument);
      index += 1;
      continue;
    }

    if (argument === "--ready-timeout-ms") {
      options.readyTimeoutMs = parsePositiveInteger(requireNextArg(args, index, argument), argument);
      index += 1;
      continue;
    }

    if (argument === "--shutdown-timeout-ms") {
      options.shutdownTimeoutMs = parsePositiveInteger(requireNextArg(args, index, argument), argument);
      index += 1;
      continue;
    }

    if (argument === "--generate-snapshot") {
      options.generateSnapshot = true;
      options.jarPath = requireNextArg(args, index, argument);
      index += 1;
      continue;
    }

    if (argument.startsWith("--")) {
      throw new Error(`unknown bridge option: ${argument}`);
    }

    positional.push(argument);
  }

  if (!options.generateSnapshot) {
    options.jarPath = positional[0] ?? null;
  }

  if (!options.jarPath) {
    throw new Error("MCP backend jar path is required.");
  }

  return options;
}

function requireNextArg(args, index, optionName) {
  const value = args[index + 1];

  if (!value || value.startsWith("--")) {
    throw new Error(`${optionName} requires a value.`);
  }

  return value;
}

function parsePositiveInteger(rawValue, optionName) {
  const value = Number.parseInt(rawValue, 10);

  if (!Number.isFinite(value) || value <= 0) {
    throw new Error(`${optionName} must be a positive integer.`);
  }

  return value;
}

function loadSnapshot(snapshotPath) {
  const snapshot = JSON.parse(readFileSync(snapshotPath, "utf8"));

  if (!snapshot.initializeResult || !snapshot.toolsListResult) {
    throw new Error("MCP tools snapshot is missing required results.");
  }

  const toolCount = toolNamesFromResult(snapshot.toolsListResult).length;
  if (toolCount === 0) {
    throw new Error("MCP tools snapshot must contain at least one tool.");
  }

  return snapshot;
}

class FukurouMcpBridge {
  constructor(options, snapshot) {
    this.options = options;
    this.allowedToolNames = parseAllowedToolNames(process.env[ALLOWED_TOOLS_ENV]);
    this.snapshot = filterSnapshotTools(snapshot, this.allowedToolNames);
    this.backend = null;
    this.ready = false;
    this.exiting = false;
    this.shutdownRequested = false;
    this.handshakeStarted = false;
    this.handshakeTimer = null;
    this.shutdownTimers = [];
    this.queuedMessages = [];
    this.internalHandlers = new Map();
    this.internalIdCounter = 0;
    this.internalIdPrefix = `bridge:${process.pid}:${Date.now()}`;
  }

  start() {
    this.startBackend();
    attachLineReader(process.stdin, (line) => this.handleClientLine(line), () => this.handleClientEnd());
    this.installSignalHandler("SIGINT");
    this.installSignalHandler("SIGTERM");
  }

  startBackend() {
    this.backend = spawn("java", ["-jar", this.options.jarPath], {
      stdio: ["pipe", "pipe", "inherit"],
    });

    this.backend.on("error", (error) => {
      this.fail(`backend spawn failed code=${safeErrorCode(error)}`);
    });
    this.backend.on("exit", (code, signal) => this.handleBackendExit(code, signal));
    this.backend.stdin.on("error", () => {});

    attachLineReader(
      this.backend.stdout,
      (line) => this.handleBackendStdoutLine(line),
      () => {},
    );
  }

  installSignalHandler(signalName) {
    process.on(signalName, () => {
      if (this.exiting) return;

      this.shutdownRequested = true;
      this.closeBackendStdin();
      this.scheduleBackendTermination();
    });
  }

  handleClientLine(line) {
    let message;
    try {
      message = JSON.parse(line);
    } catch {
      this.writeClientResponse({
        jsonrpc: JSON_RPC_VERSION,
        id: null,
        error: {
          code: -32700,
          message: "Parse error",
        },
      });
      return;
    }

    this.handleClientMessage(message);
  }

  handleClientMessage(message) {
    if (!isObject(message)) return;

    if (message.method === INITIALIZE_METHOD && hasRequestId(message)) {
      this.replyFromSnapshot(message.id, this.snapshot.initializeResult);
      this.startHandshake(message.params ?? {});
      return;
    }

    if (message.method === INITIALIZED_NOTIFICATION_METHOD) {
      return;
    }

    if (message.method === TOOLS_LIST_METHOD && hasRequestId(message)) {
      if (this.ready) {
        this.forwardToolsListToBackend(message);
        return;
      }

      this.replyFromSnapshot(message.id, this.snapshot.toolsListResult);
      return;
    }

    if (message.method === PING_METHOD && hasRequestId(message)) {
      this.writeClientResponse({
        jsonrpc: JSON_RPC_VERSION,
        id: message.id,
        result: {},
      });
      return;
    }

    if (this.ready) {
      this.forwardToBackend(message);
      return;
    }

    this.queuedMessages.push(message);
  }

  startHandshake(initializeParams) {
    if (this.handshakeStarted) return;

    this.handshakeStarted = true;
    const requestId = this.nextInternalId("init");
    this.internalHandlers.set(requestId, (message) => this.handleBackendInitializeResponse(message));
    this.forwardToBackend({
      jsonrpc: JSON_RPC_VERSION,
      id: requestId,
      method: INITIALIZE_METHOD,
      params: initializeParams,
    });
    this.handshakeTimer = setTimeout(() => {
      this.fail(`backend handshake timed out elapsed_ms=${this.options.readyTimeoutMs}`);
    }, this.options.readyTimeoutMs);
  }

  handleBackendInitializeResponse(message) {
    if (message.error) {
      this.fail("backend initialize failed");
      return;
    }

    clearTimeout(this.handshakeTimer);
    this.handshakeTimer = null;
    this.forwardToBackend({
      jsonrpc: JSON_RPC_VERSION,
      method: INITIALIZED_NOTIFICATION_METHOD,
    });
    this.ready = true;
    this.flushQueuedMessages();
    this.requestBackendToolsListForComparison();
  }

  flushQueuedMessages() {
    const queuedMessages = this.queuedMessages;
    this.queuedMessages = [];
    for (const message of queuedMessages) {
      this.forwardToBackend(message);
    }
  }

  requestBackendToolsListForComparison() {
    const requestId = this.nextInternalId("tools-list");
    this.internalHandlers.set(requestId, (message) => {
      if (message.error) {
        writeBridgeError("backend tools/list comparison failed");
        return;
      }

      warnIfToolSnapshotDiffers(
        this.snapshot.toolsListResult,
        filterToolsListResult(message.result, this.allowedToolNames),
      );
    });
    this.forwardToBackend({
      jsonrpc: JSON_RPC_VERSION,
      id: requestId,
      method: TOOLS_LIST_METHOD,
      params: {},
    });
  }

  forwardToolsListToBackend(clientMessage) {
    const requestId = this.nextInternalId("client-tools-list");
    this.internalHandlers.set(requestId, (message) => {
      this.writeClientResponse({
        ...message,
        id: clientMessage.id,
        result: message.result === undefined
          ? undefined
          : filterToolsListResult(message.result, this.allowedToolNames),
      });
    });
    this.forwardToBackend({
      ...clientMessage,
      id: requestId,
    });
  }

  handleBackendStdoutLine(line) {
    let message;
    try {
      message = JSON.parse(line);
    } catch {
      writeBridgeError("backend stdout emitted non-json line");
      return;
    }

    if (typeof message.id === "string" && this.internalHandlers.has(message.id)) {
      const handler = this.internalHandlers.get(message.id);
      this.internalHandlers.delete(message.id);
      handler(message);
      return;
    }

    if (!this.ready) {
      const method = typeof message.method === "string" ? message.method : "response";
      writeBridgeError(`backend message before ready ignored method=${method}`);
      return;
    }

    this.writeClientResponse(message);
  }

  handleClientEnd() {
    this.shutdownRequested = true;
    clearTimeout(this.handshakeTimer);
    this.handshakeTimer = null;
    this.closeBackendStdin();
    this.scheduleBackendTermination();
  }

  closeBackendStdin() {
    if (!this.backend || this.backend.stdin.destroyed) return;

    try {
      this.backend.stdin.end();
    } catch {
      // EPIPE during shutdown is harmless.
    }
  }

  scheduleBackendTermination() {
    if (!this.backend || this.backend.exitCode !== null || this.backend.killed) return;

    const terminateTimer = setTimeout(() => {
      this.backend.kill("SIGTERM");
    }, this.options.shutdownTimeoutMs);
    const killTimer = setTimeout(() => {
      this.backend.kill("SIGKILL");
    }, this.options.shutdownTimeoutMs * 2);
    this.shutdownTimers.push(terminateTimer, killTimer);
  }

  handleBackendExit(code, signal) {
    this.shutdownTimers.forEach((timer) => clearTimeout(timer));
    this.shutdownTimers = [];

    if (this.shutdownRequested) {
      this.exitWithBackendStatus(code, signal);
      return;
    }

    if (!this.ready) {
      this.fail(`backend exited before ready ${formatExitStatus(code, signal)}`);
      return;
    }

    this.fail(`backend exited after ready ${formatExitStatus(code, signal)}`);
  }

  exitWithBackendStatus(code, signal) {
    if (this.exiting) return;

    this.exiting = true;
    process.exit(signal ? 1 : (code ?? 1));
  }

  fail(message) {
    if (this.exiting) return;

    this.exiting = true;
    clearTimeout(this.handshakeTimer);
    this.handshakeTimer = null;
    writeBridgeError(message);

    if (this.backend && this.backend.exitCode === null && !this.backend.killed) {
      this.backend.kill("SIGTERM");
    }

    process.exit(1);
  }

  replyFromSnapshot(id, result) {
    this.writeClientResponse({
      jsonrpc: JSON_RPC_VERSION,
      id,
      result,
    });
  }

  writeClientResponse(message) {
    writeJson(process.stdout, message);
  }

  forwardToBackend(message) {
    if (!this.backend || !this.backend.stdin.writable) {
      this.fail("backend stdin is unavailable");
      return;
    }

    writeJson(this.backend.stdin, message);
  }

  nextInternalId(kind) {
    this.internalIdCounter += 1;
    return `${this.internalIdPrefix}:${kind}:${this.internalIdCounter}`;
  }
}

async function generateSnapshot(options) {
  const backend = spawn(
    "java",
    [TEST_RUNTIME_PROPERTY, "-jar", options.jarPath],
    {
      stdio: ["pipe", "pipe", "inherit"],
      env: {
        ...process.env,
        [TEST_RUNTIME_ENV]: "true",
      },
    },
  );
  backend.stdin.on("error", () => {});

  const rpc = new SnapshotBackendRpc(backend, options.readyTimeoutMs);

  try {
    const initializeResponse = await rpc.request(INITIALIZE_METHOD, {
      protocolVersion: "2024-11-05",
      capabilities: {},
      clientInfo: {
        name: "fukurou-mcp-snapshot-generator",
        version: "0.1.0",
      },
    });
    rpc.notify(INITIALIZED_NOTIFICATION_METHOD, {});
    const toolsListResponse = await rpc.request(TOOLS_LIST_METHOD, {});
    const toolCount = toolNamesFromResult(toolsListResponse.result).length;

    if (toolCount === 0) {
      throw new Error("generated MCP tools snapshot contained zero tools");
    }

    const snapshot = {
      schemaVersion: 1,
      generatedAt: new Date().toISOString(),
      initializeResult: initializeResponse.result,
      toolsListResult: toolsListResponse.result,
    };
    writeFileSync(options.snapshotPath, `${JSON.stringify(snapshot, null, 2)}\n`, {
      mode: 0o644,
    });
  } finally {
    backend.stdin.end();
    await rpc.waitForExitOrTerminate(options.shutdownTimeoutMs);
  }
}

class SnapshotBackendRpc {
  constructor(backend, timeoutMs) {
    this.backend = backend;
    this.timeoutMs = timeoutMs;
    this.requestCounter = 0;
    this.pending = new Map();
    this.exitPromise = new Promise((resolve) => {
      backend.once("exit", (code, signal) => {
        const exitStatus = { code, signal };
        for (const pendingRequest of this.pending.values()) {
          pendingRequest.reject(new Error(`backend exited before response ${formatExitStatus(code, signal)}`));
        }
        this.pending.clear();
        resolve(exitStatus);
      });
    });
    backend.once("error", (error) => {
      for (const pendingRequest of this.pending.values()) {
        pendingRequest.reject(new Error(`backend spawn failed code=${safeErrorCode(error)}`));
      }
      this.pending.clear();
    });
    attachLineReader(
      backend.stdout,
      (line) => this.handleStdoutLine(line),
      () => {},
    );
  }

  request(method, params) {
    const id = `snapshot:${method}:${this.requestCounter += 1}`;
    const message = {
      jsonrpc: JSON_RPC_VERSION,
      id,
      method,
      params,
    };

    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new Error(`backend snapshot request timed out method=${method}`));
      }, this.timeoutMs);
      this.pending.set(id, {
        resolve: (response) => {
          clearTimeout(timer);
          resolve(response);
        },
        reject: (error) => {
          clearTimeout(timer);
          reject(error);
        },
      });
      writeJson(this.backend.stdin, message);
    });
  }

  notify(method, params) {
    writeJson(this.backend.stdin, {
      jsonrpc: JSON_RPC_VERSION,
      method,
      params,
    });
  }

  handleStdoutLine(line) {
    let message;
    try {
      message = JSON.parse(line);
    } catch {
      writeBridgeError("backend stdout emitted non-json line during snapshot generation");
      return;
    }

    if (typeof message.id !== "string" || !this.pending.has(message.id)) return;

    const pendingRequest = this.pending.get(message.id);
    this.pending.delete(message.id);

    if (message.error) {
      pendingRequest.reject(new Error("backend snapshot request failed"));
      return;
    }

    pendingRequest.resolve(message);
  }

  async waitForExitOrTerminate(timeoutMs) {
    if (this.backend.exitCode !== null) {
      return {
        code: this.backend.exitCode,
        signal: this.backend.signalCode,
      };
    }

    const exitStatus = await waitForProcessExit(this.backend, timeoutMs);
    if (exitStatus.signal) {
      throw new Error(`backend snapshot process terminated ${formatExitStatus(exitStatus.code, exitStatus.signal)}`);
    }
    if (exitStatus.code !== 0) {
      throw new Error(`backend snapshot process exited ${formatExitStatus(exitStatus.code, exitStatus.signal)}`);
    }

    return exitStatus;
  }
}

function waitForProcessExit(processHandle, timeoutMs) {
  return new Promise((resolve) => {
    let resolved = false;
    const complete = (exitStatus) => {
      if (resolved) return;

      resolved = true;
      clearTimeout(terminateTimer);
      clearTimeout(killTimer);
      resolve(exitStatus);
    };
    const terminateTimer = setTimeout(() => {
      if (!resolved) processHandle.kill("SIGTERM");
    }, timeoutMs);
    const killTimer = setTimeout(() => {
      if (!resolved) processHandle.kill("SIGKILL");
    }, timeoutMs * 2);

    processHandle.once("exit", (code, signal) => complete({ code, signal }));
  });
}

function attachLineReader(stream, onLine, onEnd) {
  let buffer = "";
  stream.setEncoding("utf8");
  stream.on("data", (chunk) => {
    buffer += chunk;

    while (true) {
      const lineBreakIndex = buffer.indexOf("\n");
      if (lineBreakIndex < 0) break;

      const line = buffer.slice(0, lineBreakIndex).replace(/\r$/, "");
      buffer = buffer.slice(lineBreakIndex + 1);
      if (line.length > 0) {
        onLine(line);
      }
    }
  });
  stream.on("end", () => {
    const remainingLine = buffer.replace(/\r$/, "");
    buffer = "";
    if (remainingLine.length > 0) {
      onLine(remainingLine);
    }
    onEnd();
  });
}

function warnIfToolSnapshotDiffers(snapshotResult, backendResult) {
  const snapshotToolNames = new Set(toolNamesFromResult(snapshotResult));
  const backendToolNames = new Set(toolNamesFromResult(backendResult));
  const missingFromBackend = [...snapshotToolNames]
    .filter((toolName) => !backendToolNames.has(toolName))
    .sort();
  const extraFromBackend = [...backendToolNames]
    .filter((toolName) => !snapshotToolNames.has(toolName))
    .sort();

  if (missingFromBackend.length === 0 && extraFromBackend.length === 0) return;

  const missing = missingFromBackend.length > 0 ? missingFromBackend.join(",") : "none";
  const extra = extraFromBackend.length > 0 ? extraFromBackend.join(",") : "none";
  writeBridgeError(`tools/list snapshot mismatch missing_from_backend=${missing} extra_from_backend=${extra}`);
}

function filterSnapshotTools(snapshot, allowedToolNames) {
  if (allowedToolNames === null) return snapshot;

  return {
    ...snapshot,
    toolsListResult: {
      ...snapshot.toolsListResult,
      tools: filterTools(snapshot.toolsListResult.tools, allowedToolNames),
    },
  };
}

function filterToolsListResult(result, allowedToolNames) {
  if (allowedToolNames === null || !isObject(result) || !Array.isArray(result.tools)) return result;

  return {
    ...result,
    tools: filterTools(result.tools, allowedToolNames),
  };
}

function filterTools(tools, allowedToolNames) {
  return tools.filter((tool) => isObject(tool) && allowedToolNames.has(tool.name));
}

function parseAllowedToolNames(rawAllowedTools) {
  if (typeof rawAllowedTools !== "string") return null;

  const toolNames = rawAllowedTools
    .split(",")
    .map((toolName) => toolName.trim())
    .filter((toolName) => toolName.length > 0);
  if (toolNames.length === 0) return null;

  return new Set(toolNames);
}

function toolNamesFromResult(result) {
  if (!isObject(result) || !Array.isArray(result.tools)) return [];

  return result.tools
    .map((tool) => (isObject(tool) && typeof tool.name === "string" ? tool.name : null))
    .filter((toolName) => toolName !== null)
    .sort();
}

function hasRequestId(message) {
  return Object.prototype.hasOwnProperty.call(message, "id");
}

function isObject(value) {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function writeJson(stream, message) {
  stream.write(`${JSON.stringify(message)}\n`);
}

function writeBridgeError(message) {
  process.stderr.write(`fukurou-mcp-bridge: ${message}\n`);
}

function safeErrorCode(error) {
  return error && typeof error.code === "string" ? error.code : "unknown";
}

function formatExitStatus(code, signal) {
  if (signal) return `signal=${signal}`;

  return `code=${code ?? "unknown"}`;
}

main().catch((error) => {
  writeBridgeError(error.message || "unexpected bridge error");
  process.exit(1);
});
