import { disconnectedContext, initialContextState, transitionCurrentContext, type ContextEnvelope, type ContextSource, type ContextState } from "./currentContextStateMachine";

export type BrowserSocket = { onmessage: ((event: MessageEvent) => unknown) | null; onclose: ((event: CloseEvent) => unknown) | null; onerror: ((event: Event) => unknown) | null; close: () => void };
type Timer = ReturnType<typeof setTimeout>;

export function isContextEnvelope(value: unknown): value is ContextEnvelope {
  if (typeof value !== "object" || value == null) return false;
  const envelope = value as Record<string, unknown>;
  return envelope.protocolVersion === 1 && ["SNAPSHOT", "UPDATE", "HEARTBEAT"].includes(String(envelope.type)) && typeof envelope.sessionId === "string" && envelope.sessionId.length > 0 && Number.isSafeInteger(envelope.sequence) && Array.isArray(envelope.sources) && envelope.sources.every(isContextSource);
}

function isContextSource(value: unknown): value is ContextSource {
  if (typeof value !== "object" || value == null) return false;
  const source = value as Record<string, unknown>;
  return typeof source.source === "string" && typeof source.freshness === "string" && (source.value == null || (typeof source.value === "object" && !Array.isArray(source.value) && Object.values(source.value).every((item) => typeof item === "string")));
}

export function startCurrentContextClient(options: { url: string; createSocket: (url: string) => BrowserSocket; onContext: (state: ContextState) => void; setTimer?: typeof setTimeout; clearTimer?: typeof clearTimeout }) {
  const setTimer = options.setTimer ?? setTimeout;
  const clearTimer = options.clearTimer ?? clearTimeout;
  let state = initialContextState;
  let socket: BrowserSocket | null = null;
  let reconnectTimer: Timer | null = null;
  let heartbeatTimer: Timer | null = null;
  let stopped = false;
  const publish = (next: ContextState) => { state = next; options.onContext(next); };
  const armTimeout = () => { if (heartbeatTimer) clearTimer(heartbeatTimer); heartbeatTimer = setTimer(() => socket?.close(), 45_000); };
  const connect = () => {
    if (stopped) return;
    socket = options.createSocket(options.url);
    socket.onmessage = (event) => {
      try {
        const parsed: unknown = JSON.parse(String(event.data));
        if (!isContextEnvelope(parsed)) throw new Error("malformed current context envelope");
        const transition = transitionCurrentContext(state, parsed);
        publish(transition.context);
        if (transition.close) socket?.close(); else armTimeout();
      } catch { publish(disconnectedContext(state)); socket?.close(); }
    };
    socket.onclose = () => { if (stopped) return; publish(disconnectedContext(state)); reconnectTimer = setTimer(connect, 1_000); };
    socket.onerror = () => socket?.close();
    armTimeout();
  };
  connect();
  return () => { stopped = true; if (reconnectTimer) clearTimer(reconnectTimer); if (heartbeatTimer) clearTimer(heartbeatTimer); socket?.close(); };
}
