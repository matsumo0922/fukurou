import { queryOptions } from "@tanstack/react-query";
import { getJsonResponse, getTextResponse } from "./client";
import type { components } from "./openapi-types";

export type HealthResponse = components["schemas"]["HealthResponse"];

export type ReadinessResponse = components["schemas"]["ReadinessResponse"];

export type OpsLlmAuthResponse = components["schemas"]["OpsLlmAuthResponse"];

export type SystemEndpointSnapshot = {
  label: string;
  path: "/health" | "/health/ready" | "/revision" | "/ops/llm-auth";
  httpStatus: number;
};

export type SystemStatusSnapshot = {
  health: HealthResponse;
  healthHttpStatus: number;
  readiness: ReadinessResponse;
  readinessHttpStatus: number;
  revision: string;
  revisionHttpStatus: number;
  llmAuth: OpsLlmAuthResponse;
  llmAuthHttpStatus: number;
  fetchedAt: string;
  endpoints: SystemEndpointSnapshot[];
};

export const systemStatusQuery = queryOptions({
  queryKey: ["system-status"],
  queryFn: fetchSystemStatus,
  staleTime: 15_000,
  refetchInterval: 30_000,
});

export async function fetchSystemStatus(): Promise<SystemStatusSnapshot> {
  const [healthResponse, readinessResponse, revisionResponse, llmAuthResponse] = await Promise.all([
    getJsonResponse("/health"),
    getJsonResponse("/health/ready", [200, 503]),
    getTextResponse("/revision"),
    getJsonResponse("/ops/llm-auth"),
  ]);

  return {
    health: healthResponse.data,
    healthHttpStatus: healthResponse.status,
    readiness: readinessResponse.data,
    readinessHttpStatus: readinessResponse.status,
    revision: normalizeRevision(revisionResponse.data),
    revisionHttpStatus: revisionResponse.status,
    llmAuth: llmAuthResponse.data,
    llmAuthHttpStatus: llmAuthResponse.status,
    fetchedAt: new Date().toISOString(),
    endpoints: [
      {
        label: "Health",
        path: "/health",
        httpStatus: healthResponse.status,
      },
      {
        label: "Readiness",
        path: "/health/ready",
        httpStatus: readinessResponse.status,
      },
      {
        label: "Revision",
        path: "/revision",
        httpStatus: revisionResponse.status,
      },
      {
        label: "CLI Auth",
        path: "/ops/llm-auth",
        httpStatus: llmAuthResponse.status,
      },
    ],
  };
}

function normalizeRevision(revision: string): string {
  return revision.trim() || "unknown";
}
