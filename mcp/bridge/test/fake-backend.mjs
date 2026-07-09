#!/usr/bin/env node
import { appendFileSync } from "node:fs";
import process from "node:process";

const JSON_RPC_VERSION = "2.0";
const eventsFile = process.env.FAKE_BACKEND_EVENTS_FILE;
const tools = parseTools(process.env.FAKE_BACKEND_TOOLS);
const initializeDelayMs = Number.parseInt(process.env.FAKE_BACKEND_INIT_DELAY_MS ?? "0", 10);
const toolsListDelayMs = Number.parseInt(process.env.FAKE_BACKEND_TOOLS_LIST_DELAY_MS ?? "0", 10);

if (process.env.FAKE_BACKEND_NON_JSON_STDOUT === "true") {
  process.stdout.write("backend boot banner\n");
}

if (process.env.FAKE_BACKEND_STDERR_LINE) {
  process.stderr.write(`${process.env.FAKE_BACKEND_STDERR_LINE}\n`);
}

if (process.env.FAKE_BACKEND_EXIT_EARLY === "true") {
  setTimeout(() => process.exit(2), 10);
}

attachLineReader(process.stdin, handleLine, () => {
  process.exit(0);
});

function handleLine(line) {
  const message = JSON.parse(line);
  recordMessage(message);

  if (message.method === "notifications/initialized" &&
    process.env.FAKE_BACKEND_EXIT_AFTER_INITIALIZED === "true") {
    setTimeout(() => process.exit(3), 10);
    return;
  }

  if (message.method === "initialize") {
    setTimeout(() => {
      writeJson({
        jsonrpc: JSON_RPC_VERSION,
        id: message.id,
        result: {
          protocolVersion: message.params?.protocolVersion ?? "2024-11-05",
          capabilities: {
            tools: {},
          },
          serverInfo: {
            name: "fake-fukurou-backend",
            version: "0.1.0",
          },
        },
      });
    }, initializeDelayMs);
    return;
  }

  if (message.method === "tools/list") {
    setTimeout(() => {
      writeJson({
        jsonrpc: JSON_RPC_VERSION,
        id: message.id,
        result: {
          tools: tools.map((toolName) => ({
            name: toolName,
            description: `${toolName} description`,
            inputSchema: {
              type: "object",
              properties: {},
            },
          })),
        },
      });
    }, toolsListDelayMs);
    return;
  }

  if (message.method === "tools/call") {
    writeJson({
      jsonrpc: JSON_RPC_VERSION,
      id: message.id,
      result: {
        content: [
          {
            type: "text",
            text: `called ${message.params?.name ?? "unknown"}`,
          },
        ],
        structuredContent: {
          name: message.params?.name ?? null,
        },
        isError: false,
      },
    });
    return;
  }

  if (message.method && Object.prototype.hasOwnProperty.call(message, "id")) {
    writeJson({
      jsonrpc: JSON_RPC_VERSION,
      id: message.id,
      result: {
        method: message.method,
        params: message.params ?? null,
      },
    });
  }
}

function parseTools(rawValue) {
  if (!rawValue) return ["snapshot_tool", "backend_tool"];

  return rawValue
    .split(",")
    .map((toolName) => toolName.trim())
    .filter((toolName) => toolName.length > 0);
}

function recordMessage(message) {
  if (!eventsFile) return;

  appendFileSync(
    eventsFile,
    `${JSON.stringify({
      id: message.id,
      method: message.method ?? null,
      params: message.params ?? null,
    })}\n`,
  );
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

function writeJson(message) {
  process.stdout.write(`${JSON.stringify(message)}\n`);
}
