export type ContextSource = { source: string; freshness: string; value: Record<string, string> | null };
export type ContextState = { state: string; sessionId: string | null; sequence: number | null; sources: ContextSource[] };
export type ContextEnvelope = { protocolVersion: number; type: string; sessionId: string; sequence: number; sources: ContextSource[] };
export type ContextTransition = { context: ContextState; close: boolean };

export const initialContextState: ContextState = { state: "CONNECTING", sessionId: null, sequence: null, sources: [] };

export function transitionCurrentContext(current: ContextState, envelope: ContextEnvelope): ContextTransition {
  const fullSnapshot = envelope.protocolVersion === 1 && envelope.type === "SNAPSHOT" && envelope.sequence === 1 && envelope.sessionId.length > 0;
  if (current.state === "RESYNCING" || current.sessionId == null) {
    if (!fullSnapshot) return { context: { ...current, state: "RESYNCING" }, close: true };
    return { context: { state: "CONNECTED", sessionId: envelope.sessionId, sequence: 1, sources: envelope.sources }, close: false };
  }
  const valid = envelope.protocolVersion === 1 && envelope.sessionId === current.sessionId && envelope.sequence === (current.sequence ?? 0) + 1 && ["UPDATE", "HEARTBEAT"].includes(envelope.type);
  if (!valid) return { context: { ...current, state: "RESYNCING" }, close: true };
  return { context: { ...current, state: "CONNECTED", sequence: envelope.sequence, sources: envelope.type === "UPDATE" ? envelope.sources : current.sources }, close: false };
}

export function disconnectedContext(current: ContextState): ContextState { return { ...current, state: "RESYNCING" }; }
