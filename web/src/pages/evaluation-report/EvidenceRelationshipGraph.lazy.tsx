import { Component, lazy, Suspense, useMemo, useState, type ReactNode } from "react";
import type { EvaluationReport } from "../../api/evaluationReport";
import { EvidencePathsTable } from "./EvidencePathsTable";
import { filterEvidencePaths, projectEvidencePaths } from "./evidenceProjection";

const EvidenceRelationshipGraph = lazy(() => import("./EvidenceRelationshipGraph"));

export function LazyEvidenceRelationshipGraph(props: { report: EvaluationReport; selectedClaim: string | null; onSelectClaim: (claimId: string) => void }) {
  const [search, setSearch] = useState("");
  const [status, setStatus] = useState("ALL");
  const allPaths = useMemo(() => projectEvidencePaths(props.report), [props.report]);
  const paths = useMemo(() => filterEvidencePaths(allPaths, search, status, props.selectedClaim), [allPaths, search, status, props.selectedClaim]);
  return <section className="report-panel report-panel--wide"><div className="console-toolbar"><label>Search paths <input aria-label="Search evidence paths" value={search} onChange={(event) => setSearch(event.target.value)} /></label><label>Status <select aria-label="Filter evidence status" value={status} onChange={(event) => setStatus(event.target.value)}><option>ALL</option><option>VERIFIED</option><option>CONFLICT</option><option>INSUFFICIENT_EVIDENCE</option><option>FACT_MISSING</option><option>NOT_VERIFIABLE</option></select></label>{props.selectedClaim && <button onClick={() => props.onSelectClaim("")}>SHOW ALL PATHS</button>}</div><GraphErrorBoundary><Suspense fallback={<div className="console-empty">Evidence graph loading. Table evidence remains available.</div>}><EvidenceRelationshipGraph {...props} paths={paths} /></Suspense></GraphErrorBoundary><EvidencePathsTable report={props.report} paths={paths} /></section>;
}

class GraphErrorBoundary extends Component<{ children: ReactNode }, { failed: boolean }> {
  state = { failed: false };
  static getDerivedStateFromError() { return { failed: true }; }
  componentDidCatch() { /* table remains authoritative */ }
  render() { return this.state.failed ? <div role="alert">Graph canvas unavailable. Use the complete evidence paths table.</div> : this.props.children; }
}
