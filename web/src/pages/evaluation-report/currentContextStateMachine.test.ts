import { describe, expect, it } from "vitest";
import { disconnectedContext, initialContextState, transitionCurrentContext } from "./currentContextStateMachine";

const snapshot = (sessionId = "connection-a") => ({ protocolVersion: 1, type: "SNAPSHOT", sessionId, sequence: 1, sources: [{ source: "MARKET_QUOTE", freshness: "FRESH", value: null }] });

class FakeWebSocket {
  context = initialContextState;
  closeCount = 0;
  emit(envelope: ReturnType<typeof snapshot>) {
    const transition = transitionCurrentContext(this.context, envelope);
    this.context = transition.context;
    if (transition.close) this.closeCount += 1;
  }
  timeout() { this.context = disconnectedContext(this.context); this.closeCount += 1; }
}

describe("current context state machine", () => {
  it("rejects a gap and every following update until a new connection snapshot", () => {
    const socket = new FakeWebSocket();
    socket.emit(snapshot());
    socket.emit({ ...snapshot(), type: "UPDATE", sequence: 3 });
    socket.emit({ ...snapshot(), type: "UPDATE", sequence: 4 });
    expect(socket.closeCount).toBe(2);
    socket.emit(snapshot("connection-b"));
    expect(socket.context).toMatchObject({ state: "CONNECTED", sessionId: "connection-b", sequence: 1 });
  });

  it("resyncs on backend session change, malformed protocol, and heartbeat timeout disconnect", () => {
    const connected = transitionCurrentContext(initialContextState, snapshot()).context;
    expect(transitionCurrentContext(connected, { ...snapshot("connection-b"), type: "UPDATE", sequence: 2 }).close).toBe(true);
    expect(transitionCurrentContext(connected, { ...snapshot(), protocolVersion: 2, type: "UPDATE", sequence: 2 }).close).toBe(true);
    const socket = new FakeWebSocket();
    socket.context = connected;
    socket.timeout();
    expect(socket.context.state).toBe("RESYNCING");
  });
});
