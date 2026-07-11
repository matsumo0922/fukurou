import { getJsonByPath } from "./client";

export type ReportFact = { factId: string; value: string | null; unit: string | null; availability: string; sourceIds: string[] };
export type ReportClaim = { claimId: string; type: string; factIds: string[]; asserted: string };
export type ClaimValidation = { claimId: string; status: string; asserted: string; actual: string | null; factIds: string[]; code: string };
export type OutcomeRidgeGroup = { groupKey: string; label: string; tradeCount: number; availableRCount: number; missingRCount: number; underflowCount: number; overflowCount: number; bins: { lowerInclusive: string; upperExclusive: string; count: number }[]; medianR: string | null; sampleState: string };
export type EvaluationReport = {
  jobId: string;
  revisionId: string;
  revisionNumber: number;
  scopeKey: string;
  status: string;
  period: { from: string; toInclusive: string; timezone: string };
  inputAsOf: string;
  inputHash: string;
  snapshotId: string;
  generatedAt: string;
  provider: string;
  model: string;
  title: string;
  segments: { segmentId: string; kind: string; text: string; claimIds: string[] }[];
  claims: ReportClaim[];
  validation: ClaimValidation[];
  facts: ReportFact[];
  sources: { sourceId: string; observedAt: string; freshness: string }[];
  outcomeRidge: { catalogVersion: string; observationKind: string; domain: { minInclusive: string; maxExclusive: string; binWidth: string }; referenceLines: string[]; groupings: { groupBy: string; groups: OutcomeRidgeGroup[] }[] };
  truncated: boolean;
};

export async function fetchDefaultReport(days: number): Promise<EvaluationReport | null> {
  const response = await fetch(`/evaluation/reports/default?days=${days}`, { headers: { Accept: "application/json" } });
  if (response.status === 404) return null;
  if (!response.ok) throw new Error(`report request failed (${response.status})`);
  return response.json() as Promise<EvaluationReport>;
}

export async function generateReport(days: number): Promise<void> {
  const response = await fetch("/evaluation/reports/jobs", {
    method: "POST",
    headers: { Accept: "application/json", "Content-Type": "application/json" },
    body: JSON.stringify({ days }),
  });
  if (!response.ok) throw new Error(`generation failed (${response.status})`);
}

export function reportQuery(days: number) {
  return { queryKey: ["evaluation-report", days], queryFn: () => fetchDefaultReport(days), staleTime: Infinity };
}

void getJsonByPath;
