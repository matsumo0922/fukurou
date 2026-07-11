import type { EvaluationReport } from "../../api/evaluationReport";

export type EvidencePath = { segment: string; claim: string; status: string; fact: string; source: string; chart: string };

export function projectEvidencePaths(report: EvaluationReport): EvidencePath[] {
  const validation = new Map(report.validation.map((item) => [item.claimId, item.status]));
  const chartsByFact = new Map<string, string[]>();
  report.chartIndex.forEach((chart) => chart.factIds.forEach((factId) => chartsByFact.set(factId, [...(chartsByFact.get(factId) ?? []), chart.chartId])));
  return report.claims.flatMap((claim) => {
    const boundSegments = report.segments.filter((segment) => segment.claimIds.includes(claim.claimId)).map((segment) => segment.segmentId);
    const segments = boundSegments.length ? boundSegments : ["UNBOUND"];
    return claim.factIds.flatMap((factId) => {
      const fact = report.facts.find((item) => item.factId === factId);
      const sources = fact?.sourceIds.length ? fact.sourceIds : ["SOURCE_UNAVAILABLE"];
      const charts = chartsByFact.get(factId) ?? ["CHART_UNAVAILABLE"];
      return segments.flatMap((segment) => sources.flatMap((source) => charts.map((chart) => ({ segment, claim: claim.claimId, status: humanStatus(validation.get(claim.claimId) ?? "NOT_VERIFIABLE"), fact: factId, source, chart }))));
    });
  });
}

export function filterEvidencePaths(paths: EvidencePath[], search: string, status: string, selectedClaim: string | null): EvidencePath[] {
  const needle = search.trim().toLowerCase();
  return paths.filter((path) => (!selectedClaim || path.claim === selectedClaim) && (status === "ALL" || path.status === humanStatus(status)) && (!needle || Object.values(path).some((value) => value.toLowerCase().includes(needle))));
}

export function humanStatus(status: string): string { return status.toLowerCase().replaceAll("_", " "); }
