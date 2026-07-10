import { queryOptions } from "@tanstack/react-query";
import { getJson, getJsonByPath, postJsonResponse } from "./client";
import type { components } from "./openapi-types";

export type EvaluationSummaryResponse = components["schemas"]["EvaluationSummaryResponse"];
export type EvaluationSetupsResponse = components["schemas"]["EvaluationSetupsResponse"];
export type EvaluationCalibrationResponse = components["schemas"]["EvaluationCalibrationResponse"];
export type EvaluationBenchmarkResponse = components["schemas"]["EvaluationBenchmarkResponse"];
export type EvaluationCostsResponse = components["schemas"]["EvaluationCostsResponse"];
export type OpsAccountResponse = components["schemas"]["OpsAccountResponse"];
export type OpsActivityCatalogItemResponse = components["schemas"]["OpsActivityCatalogItemResponse"];
export type OpsActivityCatalogResponse = components["schemas"]["OpsActivityCatalogResponse"];
export type OpsActivityDetailsResponse = components["schemas"]["OpsActivityDetailsResponse"];
export type OpsActivityEventResponse = components["schemas"]["OpsActivityEventResponse"];
export type OpsActivityMetadataResponse = components["schemas"]["OpsActivityMetadataResponse"];
export type OpsActivityResponse = components["schemas"]["OpsActivityResponse"];
export type OpsAuditEventResponse = components["schemas"]["OpsAuditEventResponse"];
export type OpsDecisionResponse = components["schemas"]["OpsDecisionResponse"];
export type OpsExecutionResponse = components["schemas"]["OpsExecutionResponse"];
export type OpsHaltLevel = components["schemas"]["OpsHaltRequest"]["level"];
export type OpsLlmAuthLoginResponse = components["schemas"]["OpsLlmAuthLoginResponse"];
export type OpsLlmAuthProviderResponse = components["schemas"]["OpsLlmAuthProviderResponse"];
export type OpsLlmAuthResponse = components["schemas"]["OpsLlmAuthResponse"];
export type OpsLlmAuthTokenSubmitResponse = components["schemas"]["OpsLlmAuthTokenSubmitResponse"];
export type OpsPositionsResponse = components["schemas"]["OpsPositionsResponse"];
export type OpsRiskStateResponse = components["schemas"]["OpsRiskStateResponse"];
export type RuntimeConfigGroup = components["schemas"]["RuntimeConfigGroup"];
export type RuntimeConfigItem = components["schemas"]["RuntimeConfigItem"];
export type RuntimeConfigSnapshot = components["schemas"]["RuntimeConfigSnapshot"];
export type RuntimeConfigSnapshotWarning = components["schemas"]["RuntimeConfigSnapshotWarning"];
export type RuntimeConfigActivationResult = components["schemas"]["RuntimeConfigActivationResult"];
export type RuntimeConfigValidationError = components["schemas"]["RuntimeConfigValidationError"];
export type RuntimeConfigValidationResult = components["schemas"]["RuntimeConfigValidationResult"];
export type RuntimeConfigVersionDetail = components["schemas"]["RuntimeConfigVersionDetail"];
export type RuntimeConfigVersionSummary = components["schemas"]["RuntimeConfigVersionSummary"];
export type OpsTriggerResponse = components["schemas"]["OpsTriggerResponse"];
export type OpsDecisionRunsResponse = components["schemas"]["OpsDecisionRunsResponse"];
export type OpsDecisionRunSummaryResponse = components["schemas"]["OpsDecisionRunSummaryResponse"];
export type OpsDecisionRunDetailResponse = components["schemas"]["OpsDecisionRunDetailResponse"];
export type DecisionRunOutcome = "EXECUTED" | "DENIED" | "NO_TRADE" | "INTERRUPTED" | "RUNNING" | "FAILED";
export type DecisionRunOutcomeFilter = DecisionRunOutcome | "ALL";

export const DECISION_RUN_FILTER_STORAGE_KEY = "fukurou.web.activity.run-filter.v2";
export const DECISION_RUN_OUTCOME_FILTERS = [
  "ALL",
  "EXECUTED",
  "DENIED",
  "NO_TRADE",
  "INTERRUPTED",
  "RUNNING",
  "FAILED",
] as const satisfies readonly DecisionRunOutcomeFilter[];
export const DECISION_RUN_PAGE_LIMIT = 50;
export const DECISION_RUN_REFETCH_INTERVAL_MILLIS = 30_000;

export type LlmAuthProvider = "claude" | "codex";
export type ActivityTimelineSource = "audit" | "decision" | "execution";
export type ActivityTimelineSourceFilter = ActivityTimelineSource | "all";

export type ActivityTimelineEvent = Omit<OpsActivityEventResponse, "metadata" | "source"> & {
  source: ActivityTimelineSource;
  metadata: OpsActivityMetadataResponse[];
  details?: OpsActivityDetailsResponse | null;
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

export const LLM_AUTH_PROVIDERS = ["claude", "codex"] as const;

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

export const opsLlmAuthQuery = queryOptions({
  queryKey: ["ops", "llm-auth"],
  queryFn: () => getJson("/ops/llm-auth"),
  staleTime: 15_000,
  refetchInterval: 30_000,
});

export const opsRuntimeConfigQuery = queryOptions({
  queryKey: ["ops", "runtime-config"],
  queryFn: () => getJson("/ops/runtime-config"),
  staleTime: 60_000,
  refetchInterval: 60_000,
});

export const opsActivityCatalogQuery = queryOptions({
  queryKey: ["ops", "activity-catalog"],
  queryFn: () => getJson("/ops/activity/catalog"),
  staleTime: 300_000,
});

export async function fetchDecisionRuns({
  before,
  outcome,
}: {
  before?: string | null;
  outcome?: DecisionRunOutcome | null;
} = {}): Promise<OpsDecisionRunsResponse> {
  const searchParams = new URLSearchParams({ limit: String(DECISION_RUN_PAGE_LIMIT) });
  if (before) searchParams.set("before", before);
  if (outcome) searchParams.set("outcome", outcome);

  return getJson(`/ops/runs?${searchParams.toString()}`);
}

export function decisionRunRefetchInterval(pageCount: number): number | false {
  return pageCount <= 1 ? DECISION_RUN_REFETCH_INTERVAL_MILLIS : false;
}

export function opsDecisionRunDetailQuery(invocationId: string | null) {
  return queryOptions({
    queryKey: ["ops", "decision-run", invocationId],
    queryFn: () => getJsonByPath<OpsDecisionRunDetailResponse>(`/ops/runs/${encodeURIComponent(invocationId ?? "")}`),
    enabled: invocationId !== null,
    staleTime: 15_000,
  });
}

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

export async function requestOpsLlmAuthLogin({
  provider,
  reason,
}: {
  provider: LlmAuthProvider;
  reason: string;
}): Promise<OpsLlmAuthLoginResponse> {
  const path = `/ops/llm-auth/${provider}/login` as "/ops/llm-auth/{provider}/login";
  const response = await postJsonResponse(path, { reason }, [202] as const);

  return response.data;
}

export function opsLlmAuthLoginSessionQuery(provider: LlmAuthProvider, sessionId: string) {
  const path = `/ops/llm-auth/${provider}/login/${encodeURIComponent(sessionId)}` as "/ops/llm-auth/{provider}/login/{sessionId}";

  return queryOptions({
    queryKey: ["ops", "llm-auth", provider, "login", sessionId],
    queryFn: () => getJson(path),
    staleTime: 0,
    refetchInterval: (query) => {
      const status = query.state.data?.status;

      return status && isTerminalLlmAuthLoginStatus(status) ? false : 2_000;
    },
  });
}

export async function requestOpsLlmAuthTokenCodeSubmit({
  provider,
  sessionId,
  tokenCode,
}: {
  provider: LlmAuthProvider;
  sessionId: string;
  tokenCode: string;
}): Promise<OpsLlmAuthTokenSubmitResponse> {
  const path = `/ops/llm-auth/${provider}/login/${encodeURIComponent(sessionId)}/token` as "/ops/llm-auth/{provider}/login/{sessionId}/token";
  const response = await postJsonResponse(path, { code: tokenCode }, [202] as const);

  return response.data;
}

export async function createRuntimeConfigDraft({
  values,
  baseVersionId = null,
  note = null,
}: {
  values: Record<string, string>;
  baseVersionId?: string | null;
  note?: string | null;
}): Promise<RuntimeConfigVersionDetail> {
  const response = await postJsonResponse("/ops/runtime-config/drafts", { baseVersionId, values, note }, [201] as const);

  return response.data;
}

export async function validateRuntimeConfigDraft(versionId: string): Promise<RuntimeConfigVersionDetail> {
  const path = `/ops/runtime-config/drafts/${encodeURIComponent(versionId)}/validate` as "/ops/runtime-config/drafts/{versionId}/validate";
  const response = await postJsonResponse(path, {}, [200] as const);

  return response.data;
}

export async function activateRuntimeConfigDraft(versionId: string) {
  const path = `/ops/runtime-config/drafts/${encodeURIComponent(versionId)}/activate` as "/ops/runtime-config/drafts/{versionId}/activate";

  return postJsonResponse(path, {}, [200, 409] as const);
}

export async function rollbackRuntimeConfigVersion(versionId: string) {
  const path = `/ops/runtime-config/versions/${encodeURIComponent(versionId)}/rollback` as "/ops/runtime-config/versions/{versionId}/rollback";

  return postJsonResponse(path, {}, [200, 409] as const);
}

function isTerminalLlmAuthLoginStatus(status: OpsLlmAuthLoginResponse["status"]): boolean {
  return status === "succeeded" || status === "failed" || status === "timed_out";
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
    ? value.auditEventTypes.filter(isNonEmptyString)
    : [];

  return {
    source,
    auditEventTypes,
  };
}

export function pruneActivityTimelineFilters(
  filters: ActivityTimelineFilters,
  catalog: OpsActivityCatalogResponse,
): ActivityTimelineFilters {
  const knownAuditEventTypes = new Set(catalog.auditEventTypes.map((item) => item.value));

  return {
    ...filters,
    auditEventTypes: filters.auditEventTypes.filter((eventType) => knownAuditEventTypes.has(eventType)),
  };
}

export function activityTimelineRequestFilters(
  filters: ActivityTimelineFilters,
  catalog: OpsActivityCatalogResponse | null,
): ActivityTimelineFilters {
  if (!catalog) {
    return {
      ...filters,
      auditEventTypes: [],
    };
  }

  return pruneActivityTimelineFilters(filters, catalog);
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

function isNonEmptyString(value: unknown): value is string {
  return typeof value === "string" && value.trim().length > 0;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}
