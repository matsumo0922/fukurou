import { afterEach, describe, expect, it, vi } from "vitest";
import { isContextEnvelope, startCurrentContextClient, type BrowserSocket } from "./currentContextClient";
import type { ContextState } from "./currentContextStateMachine";

class FakeBrowserWebSocket implements BrowserSocket {
  onmessage: ((event: MessageEvent) => unknown) | null = null;
  onclose: ((event: CloseEvent) => unknown) | null = null;
  onerror: ((event: Event) => unknown) | null = null;
  closed = false;
  close() { if (this.closed) return; this.closed = true; this.onclose?.(new CloseEvent("close")); }
  emit(value: unknown) { this.onmessage?.(new MessageEvent("message", { data: JSON.stringify(value) })); }
}

const snapshot = (sessionId = "connection-a") => ({ protocolVersion: 1, type: "SNAPSHOT", sessionId, sequence: 1, sources: [{ source: "MARKET_QUOTE", freshness: "FRESH", value: { bid: "1" } }] });

describe("browser current context client", () => {
  afterEach(() => vi.useRealTimers());

  it("validates payload shape and reconnects after a gap before accepting a new snapshot", () => {
    vi.useFakeTimers();
    const sockets: FakeBrowserWebSocket[] = [];
    const states: ContextState[] = [];
    const stop = startCurrentContextClient({ url: "ws://localhost/context", createSocket: () => { const socket = new FakeBrowserWebSocket(); sockets.push(socket); return socket; }, onContext: (state) => states.push(state) });
    sockets[0].emit(snapshot());
    sockets[0].emit({ ...snapshot(), type: "UPDATE", sequence: 3 });
    expect(sockets[0].closed).toBe(true);
    vi.advanceTimersByTime(1_000);
    expect(sockets).toHaveLength(2);
    sockets[1].emit({ ...snapshot("connection-b"), sources: undefined });
    expect(sockets[1].closed).toBe(true);
    vi.advanceTimersByTime(1_000);
    sockets[2].emit(snapshot("connection-c"));
    expect(states.at(-1)).toMatchObject({ state: "CONNECTED", sessionId: "connection-c", sequence: 1 });
    stop();
  });

  it("closes after 45 seconds without a heartbeat and reconnects", () => {
    vi.useFakeTimers();
    const sockets: FakeBrowserWebSocket[] = [];
    const stop = startCurrentContextClient({ url: "ws://localhost/context", createSocket: () => { const socket = new FakeBrowserWebSocket(); sockets.push(socket); return socket; }, onContext: () => undefined });
    sockets[0].emit(snapshot());
    vi.advanceTimersByTime(45_000);
    expect(sockets[0].closed).toBe(true);
    vi.advanceTimersByTime(1_000);
    expect(sockets).toHaveLength(2);
    stop();
  });

  it("rejects a snapshot without sources", () => {
    expect(isContextEnvelope({ ...snapshot(), sources: undefined })).toBe(false);
  });
});
