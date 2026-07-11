import type { EvaluationReport } from "../../api/evaluationReport";

export function EvidencePathsTable({ report }: { report: EvaluationReport }) {
  const validation = new Map(report.validation.map((item) => [item.claimId, item.status]));
  const chartsByFact = new Map<string, string[]>();
  report.chartIndex.forEach((chart) => chart.factIds.forEach((factId) => {
    chartsByFact.set(factId, [...(chartsByFact.get(factId) ?? []), chart.chartId]);
  }));
  const paths = report.claims.flatMap((claim) => claim.factIds.map((factId) => {
    const fact = report.facts.find((item) => item.factId === factId);
    return {
      segment: report.segments.find((segment) => segment.claimIds.includes(claim.claimId))?.segmentId ?? "UNBOUND",
      claim: claim.claimId,
      status: (validation.get(claim.claimId) ?? "NOT_VERIFIABLE").toLowerCase().replaceAll("_", " "),
      fact: factId,
      sources: fact?.sourceIds.join(", ") || "SOURCE_UNAVAILABLE",
      charts: chartsByFact.get(factId)?.join(", ") || "CHART_UNAVAILABLE",
    };
  }));
  return <div className="console-table-wrap"><table><caption>Evidence relationship text representation; authoritative when the graph canvas is unavailable</caption><thead><tr><th>Segment</th><th>Claim</th><th>Status</th><th>Fact</th><th>Source</th><th>Chart</th></tr></thead><tbody>{paths.map((path, index) => <tr key={`${path.claim}:${path.fact}:${index}`}><td>{path.segment}</td><td>{path.claim}</td><td>{path.status}</td><td>{path.fact}</td><td>{path.sources}</td><td>{path.charts}</td></tr>)}</tbody></table></div>;
}
