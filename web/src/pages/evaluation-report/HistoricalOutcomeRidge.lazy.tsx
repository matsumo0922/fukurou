import { lazy, Suspense } from "react";
import type { EvaluationReport } from "../../api/evaluationReport";

const HistoricalOutcomeRidge = lazy(() =>
  import("./HistoricalOutcomeRidge").then((module) => ({ default: module.HistoricalOutcomeRidge })),
);

export function LazyHistoricalOutcomeRidge({ report }: { report: EvaluationReport }) {
  return <Suspense fallback={<section className="report-panel">Historical outcome distribution loading…</section>}>
    <HistoricalOutcomeRidge report={report} />
  </Suspense>;
}
