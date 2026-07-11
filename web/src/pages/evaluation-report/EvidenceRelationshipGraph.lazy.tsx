import { lazy, Suspense } from "react";
import type { EvaluationReport } from "../../api/evaluationReport";

const EvidenceRelationshipGraph = lazy(() => import("./EvidenceRelationshipGraph"));

export function LazyEvidenceRelationshipGraph(props: { report: EvaluationReport; selectedClaim: string | null; onSelectClaim: (claimId: string) => void }) {
  return <Suspense fallback={<section className="report-panel report-panel--wide console-empty">Evidence graph loading. Report and table evidence remain available.</section>}><EvidenceRelationshipGraph {...props} /></Suspense>;
}
