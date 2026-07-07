import { queryOptions } from "@tanstack/react-query";
import { getJson, postJsonResponse } from "./client";
import type { components } from "./openapi-types";

export type EvaluationSummaryResponse = components["schemas"]["EvaluationSummaryResponse"];
export type EvaluationSetupsResponse = components["schemas"]["EvaluationSetupsResponse"];
export type EvaluationCalibrationResponse = components["schemas"]["EvaluationCalibrationResponse"];
export type EvaluationBenchmarkResponse = components["schemas"]["EvaluationBenchmarkResponse"];
export type EvaluationCostsResponse = components["schemas"]["EvaluationCostsResponse"];
export type OpsAccountResponse = components["schemas"]["OpsAccountResponse"];
export type OpsActivityEventResponse = components["schemas"]["OpsActivityEventResponse"];
export type OpsActivityMetadataResponse = components["schemas"]["OpsActivityMetadataResponse"];
export type OpsActivityResponse = components["schemas"]["OpsActivityResponse"];
export type OpsAuditEventResponse = components["schemas"]["OpsAuditEventResponse"];
export type OpsDecisionResponse = components["schemas"]["OpsDecisionResponse"];
export type OpsExecutionResponse = components["schemas"]["OpsExecutionResponse"];
export type OpsHaltLevel = components["schemas"]["OpsHaltRequest"]["level"];
export type OpsPositionsResponse = components["schemas"]["OpsPositionsResponse"];
export type OpsRiskStateResponse = components["schemas"]["OpsRiskStateResponse"];
export type OpsTriggerResponse = components["schemas"]["OpsTriggerResponse"];

export type ActivityTimelineSource = "audit" | "decision" | "execution";
export type ActivityTimelineSourceFilter = ActivityTimelineSource | "all";

export type ActivityTimelineEvent = Omit<OpsActivityEventResponse, "metadata" | "source"> & {
  source: ActivityTimelineSource;
  metadata: OpsActivityMetadataResponse[];
};

export type ActivityTimelineSnapshot = {
  events: ActivityTimelineEvent[];
  fetchedAt: string;
  limit: number;
  nextBefore: string | null;
  filters: ActivityTimelineFilters;
};

export type ActivityTimelineFilters = {
  source: ActivityTimelineSourceFilter;
  auditEventTypes: string[];
};

export const ACTIVITY_TIMELINE_FILTER_STORAGE_KEY = "fukurou.web.activity.filters.v1";

export const ACTIVITY_TIMELINE_SOURCE_FILTERS = ["all", "decision", "audit", "execution"] as const;

export const ACTIVITY_AUDIT_EVENT_TYPES = [
  "TOOL_CALL_COMPLETED",
  "TOOL_CALL_REJECTED_BY_HARD_HALT",
  "NO_TRADE_EXIT",
  "RECONCILER_STARTED",
  "RECONCILER_PASS_COMPLETED",
  "RECONCILER_PASS_FAILED",
  "RECONCILER_PASS_RECOVERED",
  "HARD_HALT_SET",
  "SOFT_HALT_SET",
  "KILL_CRITERION_BREACHED",
  "MANUAL_RESUME_REQUESTED",
  "RUNNER_PHASE_COMPLETED",
  "DAEMON_STARTED",
  "DAEMON_TRIGGER_SKIPPED",
  "DAEMON_TRIGGER_LAUNCHED",
  "DAEMON_INVOCATION_COMPLETED",
] as const;

export const DEFAULT_ACTIVITY_TIMELINE_FILTERS: ActivityTimelineFilters = {
  source: "all",
  auditEventTypes: [],
};

const ACTIVITY_TIMELINE_LIMIT = 50;

export const opsRiskStateQuery = queryOptions({
  queryKey: ["ops", "risk-state"],
  queryFn: () => getJson("/ops/risk-state"),
  staleTime: 15_000,
  refetchInterval: 30_000,
});

export const opsAccountQuery = queryOptions({
  queryKey: ["ops", "account"],
  queryFn: () => getJson("/ops/account"),
  staleTime: 15_000,
  refetchInterval: 30_000,
});

export const opsDecisionsQuery = queryOptions({
  queryKey: ["ops", "decisions"],
  queryFn: () => getJson("/ops/decisions"),
  staleTime: 15_000,
  refetchInterval: 30_000,
});

export const opsPositionsQuery = queryOptions({
  queryKey: ["ops", "positions"],
  queryFn: () => getJson("/ops/positions"),
  staleTime: 15_000,
  refetchInterval: 30_000,
});

export const evaluationSummaryQuery = queryOptions({
  queryKey: ["evaluation", "summary"],
  queryFn: () => getJson("/evaluation/summary"),
  staleTime: 60_000,
  refetchInterval: 60_000,
});

export const evaluationSetupsQuery = queryOptions({
  queryKey: ["evaluation", "setups"],
  queryFn: () => getJson("/evaluation/setups"),
  staleTime: 60_000,
  refetchInterval: 60_000,
});

export const evaluationCalibrationQuery = queryOptions({
  queryKey: ["evaluation", "calibration"],
  queryFn: () => getJson("/evaluation/calibration"),
  staleTime: 60_000,
  refetchInterval: 60_000,
});

export const evaluationBenchmarkQuery = queryOptions({
  queryKey: ["evaluation", "benchmark"],
  queryFn: () => getJson("/evaluation/benchmark"),
  staleTime: 60_000,
  refetchInterval: 60_000,
});

export const evaluationCostsQuery = queryOptions({
  queryKey: ["evaluation", "costs"],
  queryFn: () => getJson("/evaluation/costs"),
  staleTime: 60_000,
  refetchInterval: 60_000,
});

export function activityTimelineQuery(filters: ActivityTimelineFilters, before?: string, autoRefresh = true) {
  return queryOptions({
    queryKey: ["ops", "activity-timeline", filters.source, filters.auditEventTypes, before ?? null],
    queryFn: () => fetchActivityTimeline({ filters, before }),
    staleTime: 15_000,
    refetchInterval: autoRefresh ? 30_000 : false,
  });
}

export async function requestOpsHalt(level: OpsHaltLevel, reason: string): Promise<OpsRiskStateResponse> {
  const response = await postJsonResponse("/ops/halt", { level, reason }, [200] as const);

  return response.data;
}

export async function requestOpsResume(reason: string): Promise<OpsRiskStateResponse> {
  const response = await postJsonResponse("/ops/resume", { reason }, [200] as const);

  return response.data;
}

export async function requestOpsTrigger(reason: string): Promise<OpsTriggerResponse> {
  const response = await postJsonResponse("/ops/trigger", { reason }, [202] as const);

  return response.data;
}

export async function fetchActivityTimeline({
  filters,
  before,
}: {
  filters: ActivityTimelineFilters;
  before?: string;
}): Promise<ActivityTimelineSnapshot> {
  const searchParams = new URLSearchParams({
    limit: String(ACTIVITY_TIMELINE_LIMIT),
  });

  if (before) {
    searchParams.set("before", before);
  }

  if (filters.source !== "all") {
    searchParams.set("source", filters.source);
  }

  filters.auditEventTypes.forEach((eventType) => {
    searchParams.append("auditEventType", eventType);
  });

  const response = await getJson(`/ops/activity?${searchParams.toString()}`);

  return {
    events: newestFirstActivityTimelineEvents(response.events.map(normalizeActivityTimelineEvent)),
    fetchedAt: new Date().toISOString(),
    limit: response.limit,
    nextBefore: response.nextBefore ?? null,
    filters,
  };
}

export function normalizeActivityTimelineFilters(value: unknown): ActivityTimelineFilters {
  if (!isRecord(value)) {
    return DEFAULT_ACTIVITY_TIMELINE_FILTERS;
  }

  const source = isActivityTimelineSourceFilter(value.source) ? value.source : "all";
  const auditEventTypes = Array.isArray(value.auditEventTypes)
    ? value.auditEventTypes.filter(isKnownActivityAuditEventType)
    : [];

  return {
    source,
    auditEventTypes,
  };
}

export function newestFirstActivityTimelineEvents(events: ActivityTimelineEvent[]): ActivityTimelineEvent[] {
  return [...events].sort(compareTimelineEvents);
}

function normalizeActivityTimelineEvent(event: OpsActivityEventResponse): ActivityTimelineEvent {
  if (!isActivityTimelineSource(event.source)) {
    throw new Error(`Unknown activity timeline source: ${event.source}`);
  }

  return {
    ...event,
    source: event.source,
  };
}

function compareTimelineEvents(firstEvent: ActivityTimelineEvent, secondEvent: ActivityTimelineEvent): number {
  const timestampComparison = timestampMillis(secondEvent.occurredAt) - timestampMillis(firstEvent.occurredAt);

  if (timestampComparison !== 0) {
    return timestampComparison;
  }

  const sourceComparison = firstEvent.source.localeCompare(secondEvent.source);

  if (sourceComparison !== 0) {
    return sourceComparison;
  }

  return firstEvent.id.localeCompare(secondEvent.id);
}

function timestampMillis(value: string): number {
  const timestamp = new Date(value).getTime();

  return Number.isNaN(timestamp) ? 0 : timestamp;
}

function isActivityTimelineSource(value: string): value is ActivityTimelineSource {
  return value === "audit" || value === "decision" || value === "execution";
}

function isActivityTimelineSourceFilter(value: unknown): value is ActivityTimelineSourceFilter {
  return typeof value === "string" && ACTIVITY_TIMELINE_SOURCE_FILTERS.includes(value as ActivityTimelineSourceFilter);
}

function isKnownActivityAuditEventType(value: unknown): value is string {
  return typeof value === "string" && ACTIVITY_AUDIT_EVENT_TYPES.includes(value as (typeof ACTIVITY_AUDIT_EVENT_TYPES)[number]);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}
