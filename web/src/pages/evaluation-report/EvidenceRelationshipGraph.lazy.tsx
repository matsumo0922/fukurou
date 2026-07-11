import { Component, lazy, Suspense, type ReactNode } from "react";
import type { EvaluationReport } from "../../api/evaluationReport";
import { EvidencePathsTable } from "./EvidencePathsTable";

const EvidenceRelationshipGraph = lazy(() => import("./EvidenceRelationshipGraph"));

export function LazyEvidenceRelationshipGraph(props: { report: EvaluationReport; selectedClaim: string | null; onSelectClaim: (claimId: string) => void }) {
  return <section className="report-panel report-panel--wide"><GraphErrorBoundary><Suspense fallback={<div className="console-empty">Evidence graph loading. Table evidence remains available.</div>}><EvidenceRelationshipGraph {...props} /></Suspense></GraphErrorBoundary><EvidencePathsTable report={props.report} /></section>;
}

class GraphErrorBoundary extends Component<{ children: ReactNode }, { failed: boolean }> {
  state = { failed: false };
  static getDerivedStateFromError() { return { failed: true }; }
  componentDidCatch() { /* table remains authoritative */ }
  render() { return this.state.failed ? <div role="alert">Graph canvas unavailable. Use the complete evidence paths table.</div> : this.props.children; }
}
