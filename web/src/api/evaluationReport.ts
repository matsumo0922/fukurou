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
  chartIndex: { chartId: string; catalogVersion: string; factIds: string[] }[];
  outcomeRidge: { catalogVersion: string; observationKind: string; domain: { minInclusive: string; maxExclusive: string; binWidth: string }; referenceLines: string[]; groupings: { groupBy: string; groups: OutcomeRidgeGroup[] }[] };
  truncated: boolean;
};
export type ReportJob = { jobId: string; revisionId: string; status: string; stage: string; failureCode: string | null; failureMessage: string | null };
export type ReportHistoryItem = { jobId: string; revisionId: string; revisionNumber: number; status: string; requestedAt: string; pinned: boolean };

export type ReportScope = { kind: "PRESET"; days: 7 | 30 | 90 } | { kind: "CUSTOM"; from: string; toInclusive: string };

export function reportScopeKey(scope: ReportScope): string {
  return scope.kind === "PRESET" ? `PRESET:${scope.days}D` : `CUSTOM:${scope.from}:${scope.toInclusive}`;
}

export async function fetchDefaultReport(scopeKey: string): Promise<EvaluationReport | null> {
  const response = await fetch(`/evaluation/reports/default?scopeKey=${encodeURIComponent(scopeKey)}`, { headers: { Accept: "application/json" } });
  if (response.status === 404) return null;
  if (!response.ok) throw new Error(`report request failed (${response.status})`);
  return response.json() as Promise<EvaluationReport>;
}

export async function generateReport(scope: ReportScope): Promise<ReportJob> {
  const response = await fetch("/evaluation/reports/jobs", {
    method: "POST",
    headers: { Accept: "application/json", "Content-Type": "application/json" },
    body: JSON.stringify(scope),
  });
  if (!response.ok) throw new Error(`generation failed (${response.status})`);
  const accepted = await response.json() as ReportJob;
  for (;;) {
    const job = await getJsonByPath<ReportJob>(`/evaluation/reports/jobs/${accepted.jobId}`);
    if (job.status === "SUCCEEDED") return job;
    if (job.status === "FAILED") throw new Error(`${job.failureCode ?? "generation failed"}: ${job.failureMessage ?? ""}`);
    await new Promise((resolve) => setTimeout(resolve, 1000));
  }
}

export async function fetchReportHistory(scopeKey: string): Promise<ReportHistoryItem[]> {
  const response = await getJsonByPath<{ revisions: ReportHistoryItem[] }>(`/evaluation/reports/revisions?scopeKey=${encodeURIComponent(scopeKey)}`);
  return response.revisions;
}

export async function fetchReportRevision(revisionId: string): Promise<EvaluationReport> {
  return getJsonByPath(`/evaluation/reports/revisions/${encodeURIComponent(revisionId)}`);
}

export async function pinReport(scopeKey: string, revisionId: string): Promise<void> {
  const response = await fetch("/evaluation/reports/pins", { method: "PUT", headers: { Accept: "application/json", "Content-Type": "application/json" }, body: JSON.stringify({ scopeKey, revisionId }) });
  if (!response.ok) throw new Error(`pin failed (${response.status})`);
}

export function reportQuery(scopeKey: string) {
  return { queryKey: ["evaluation-report", scopeKey], queryFn: () => fetchDefaultReport(scopeKey), staleTime: Infinity };
}
