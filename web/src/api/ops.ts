import { queryOptions } from "@tanstack/react-query";
import { getJson } from "./client";
import type { components } from "./openapi-types";

export type EvaluationSummaryResponse = components["schemas"]["EvaluationSummaryResponse"];
export type EvaluationSetupsResponse = components["schemas"]["EvaluationSetupsResponse"];
export type EvaluationCalibrationResponse = components["schemas"]["EvaluationCalibrationResponse"];
export type EvaluationBenchmarkResponse = components["schemas"]["EvaluationBenchmarkResponse"];
export type EvaluationCostsResponse = components["schemas"]["EvaluationCostsResponse"];
export type OpsAccountResponse = components["schemas"]["OpsAccountResponse"];
export type OpsAuditEventResponse = components["schemas"]["OpsAuditEventResponse"];
export type OpsDecisionResponse = components["schemas"]["OpsDecisionResponse"];
export type OpsExecutionResponse = components["schemas"]["OpsExecutionResponse"];
export type OpsPositionsResponse = components["schemas"]["OpsPositionsResponse"];
export type OpsRiskStateResponse = components["schemas"]["OpsRiskStateResponse"];

export type ActivityTimelineSource = "audit" | "decision" | "execution";

export type ActivityTimelineEvent = {
  id: string;
  source: ActivityTimelineSource;
  kind: string;
  title: string;
  detail: string;
  occurredAt: string;
  metadata: {
    label: string;
    value: string;
  }[];
};

export type ActivityTimelineSnapshot = {
  events: ActivityTimelineEvent[];
  fetchedAt: string;
  limits: ActivityTimelineLimits;
};

export type ActivityTimelineLimits = {
  decisions: number;
  audit: number;
  executions: number;
  total: number;
};

const ACTIVITY_TIMELINE_DECISIONS_LIMIT = 20;
const ACTIVITY_TIMELINE_AUDIT_LIMIT = 50;
const ACTIVITY_TIMELINE_EXECUTIONS_LIMIT = 20;
const ACTIVITY_TIMELINE_TOTAL_LIMIT =
  ACTIVITY_TIMELINE_DECISIONS_LIMIT + ACTIVITY_TIMELINE_AUDIT_LIMIT + ACTIVITY_TIMELINE_EXECUTIONS_LIMIT;

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

export const activityTimelineQuery = queryOptions({
  queryKey: ["ops", "activity-timeline"],
  queryFn: fetchActivityTimeline,
  staleTime: 15_000,
  refetchInterval: 30_000,
});

async function fetchActivityTimeline(): Promise<ActivityTimelineSnapshot> {
  const [decisionsResponse, auditResponse, executionsResponse] = await Promise.all([
    getJson(`/ops/decisions?limit=${ACTIVITY_TIMELINE_DECISIONS_LIMIT}`),
    getJson(`/ops/audit?limit=${ACTIVITY_TIMELINE_AUDIT_LIMIT}`),
    getJson(`/ops/executions?limit=${ACTIVITY_TIMELINE_EXECUTIONS_LIMIT}`),
  ]);
  const events = [
    ...decisionsResponse.decisions.map(decisionToTimelineEvent),
    ...auditResponse.events.map(auditEventToTimelineEvent),
    ...executionsResponse.executions.map(executionToTimelineEvent),
  ];

  return {
    events: newestFirstActivityTimelineEvents(events),
    fetchedAt: new Date().toISOString(),
    limits: {
      decisions: ACTIVITY_TIMELINE_DECISIONS_LIMIT,
      audit: ACTIVITY_TIMELINE_AUDIT_LIMIT,
      executions: ACTIVITY_TIMELINE_EXECUTIONS_LIMIT,
      total: ACTIVITY_TIMELINE_TOTAL_LIMIT,
    },
  };
}

export function newestFirstActivityTimelineEvents(events: ActivityTimelineEvent[]): ActivityTimelineEvent[] {
  return [...events].sort(compareTimelineEvents);
}

function decisionToTimelineEvent(decision: OpsDecisionResponse): ActivityTimelineEvent {
  return {
    id: `decision:${decision.id}`,
    source: "decision",
    kind: decision.action,
    title: `${decision.action} decision`,
    detail: decision.reasonJa,
    occurredAt: decision.createdAt,
    metadata: [
      {
        label: "estimated p",
        value: decision.estimatedWinProbability,
      },
      {
        label: "setup tags",
        value: decision.setupTags.length > 0 ? decision.setupTags.join(", ") : "none",
      },
      {
        label: "no-trade conditions",
        value: decision.noTradeConditionsJa.length > 0 ? decision.noTradeConditionsJa.join(" / ") : "none",
      },
    ],
  };
}

function auditEventToTimelineEvent(event: OpsAuditEventResponse): ActivityTimelineEvent {
  return {
    id: `audit:${event.id}`,
    source: "audit",
    kind: event.eventType,
    title: event.eventType,
    detail: event.toolName,
    occurredAt: event.occurredAt,
    metadata: [
      {
        label: "tool",
        value: event.toolName,
      },
    ],
  };
}

function executionToTimelineEvent(execution: OpsExecutionResponse): ActivityTimelineEvent {
  return {
    id: `execution:${execution.executionId}`,
    source: "execution",
    kind: execution.side,
    title: `${execution.side} ${execution.symbol} execution`,
    detail: `${execution.sizeBtc} BTC at ${execution.priceJpy} JPY`,
    occurredAt: execution.executedAt,
    metadata: [
      {
        label: "realized pnl",
        value: execution.realizedPnlJpy,
      },
      {
        label: "fee",
        value: execution.feeJpy,
      },
      {
        label: "liquidity",
        value: execution.liquidity,
      },
      {
        label: "order",
        value: execution.orderId ?? "not linked",
      },
    ],
  };
}

function compareTimelineEvents(firstEvent: ActivityTimelineEvent, secondEvent: ActivityTimelineEvent): number {
  return timestampMillis(secondEvent.occurredAt) - timestampMillis(firstEvent.occurredAt);
}

function timestampMillis(value: string): number {
  const timestamp = new Date(value).getTime();

  return Number.isNaN(timestamp) ? 0 : timestamp;
}
