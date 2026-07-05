import { useQuery } from "@tanstack/react-query";
import AlertTriangle from "lucide-react/dist/esm/icons/alert-triangle.mjs";
import RefreshCw from "lucide-react/dist/esm/icons/refresh-cw.mjs";
import ServerCog from "lucide-react/dist/esm/icons/server-cog.mjs";
import { systemStatusQuery, type SystemStatusSnapshot } from "../api/system";
import { DataStrip } from "../ui/components/DataStrip";
import { EmptyState } from "../ui/components/EmptyState";
import { Metric } from "../ui/components/Metric";
import { Panel } from "../ui/components/Panel";
import { SectionHeader } from "../ui/components/SectionHeader";
import { StatusPill, type StatusTone } from "../ui/components/StatusPill";
import { describeError, formatDateTime } from "../ui/format";

export function SystemPage() {
  const statusQuery = useQuery(systemStatusQuery);

  return (
    <div className="page-stack">
      <SectionHeader
        eyebrow="App"
        title="System"
        description="Responses from /health, /health/ready, and /revision."
        action={
          <button
            className="icon-text-button icon-text-button--prominent"
            type="button"
            onClick={() => void statusQuery.refetch()}
            disabled={statusQuery.isFetching}
          >
            <RefreshCw size={16} aria-hidden="true" />
            {statusQuery.isFetching ? "Refreshing" : "Refresh"}
          </button>
        }
      />

      {statusQuery.isPending ? <SystemLoading /> : null}
      {statusQuery.isError ? <SystemError error={statusQuery.error} retried={() => void statusQuery.refetch()} /> : null}
      {statusQuery.data ? <SystemStatus data={statusQuery.data} isStale={statusQuery.isStale} /> : null}
    </div>
  );
}

function SystemStatus({ data, isStale }: { data: SystemStatusSnapshot; isStale: boolean }) {
  const readinessTone = readinessStatusTone(data.readiness.status);

  return (
    <>
      <div className="metric-grid">
        <Metric label="Health" value={data.health.status} detail={data.health.service ?? "service not reported"} />
        <Metric label="Readiness" value={data.readiness.status} detail={`HTTP ${data.readinessHttpStatus}`} />
        <Metric label="Revision" value={data.revision} detail={`HTTP ${data.revisionHttpStatus}`} />
        <div className="metric">
          <div className="metric__label-row">
            <p className="metric__label">Freshness</p>
            <StatusPill label={isStale ? "stale" : "fresh"} tone={isStale ? "warning" : "positive"} />
          </div>
          <p className="metric__value">Updated</p>
          <p className="metric__detail">{formatDateTime(data.fetchedAt)}</p>
        </div>
      </div>

      <Panel>
        <div className="panel-heading">
          <ServerCog size={18} aria-hidden="true" />
          <h2>Endpoint responses</h2>
        </div>
        <div className="endpoint-table" role="table" aria-label="System endpoint responses">
          <div className="endpoint-table__row endpoint-table__row--head" role="row">
            <span role="columnheader">Endpoint</span>
            <span role="columnheader">HTTP</span>
            <span role="columnheader">Payload</span>
          </div>
          {data.endpoints.map((endpoint) => (
            <div className="endpoint-table__row" role="row" key={endpoint.path}>
              <span className="endpoint-table__path" role="cell">
                GET {endpoint.path}
              </span>
              <span role="cell">
                <StatusPill label={String(endpoint.httpStatus)} tone={httpStatusTone(endpoint.httpStatus)} />
              </span>
              <span role="cell">{endpointPayload(endpoint.path, data)}</span>
            </div>
          ))}
        </div>
      </Panel>

      <Panel>
        <div className="panel-heading">
          <AlertTriangle size={18} aria-hidden="true" />
          <h2>Readiness timestamps</h2>
          <StatusPill label={data.readiness.status} tone={readinessTone} />
        </div>
        <DataStrip
          items={[
            {
              label: "lastReconciledAt",
              value: formatDateTime(data.readiness.lastReconciledAt),
            },
            {
              label: "lastMarketDataAt",
              value: formatDateTime(data.readiness.lastMarketDataAt),
            },
          ]}
        />
      </Panel>
    </>
  );
}

function SystemLoading() {
  return (
    <Panel>
      <div className="loading-row" role="status">
        <span className="loading-dot" aria-hidden="true" />
        <span>Loading system status</span>
      </div>
    </Panel>
  );
}

function SystemError({ error, retried }: { error: unknown; retried: () => void }) {
  return (
    <Panel>
      <EmptyState
        title="System data unavailable"
        description={describeError(error)}
        action={
          <button className="icon-text-button icon-text-button--prominent" type="button" onClick={retried}>
            <RefreshCw size={16} aria-hidden="true" />
            Retry
          </button>
        }
      />
    </Panel>
  );
}

function endpointPayload(path: SystemStatusSnapshot["endpoints"][number]["path"], data: SystemStatusSnapshot): string {
  switch (path) {
    case "/health":
      return `status=${data.health.status}`;
    case "/health/ready":
      return `status=${data.readiness.status}`;
    case "/revision":
      return data.revision;
  }
}

function httpStatusTone(status: number): StatusTone {
  if (status >= 200 && status < 300) {
    return "positive";
  }

  if (status === 503) {
    return "warning";
  }

  return "critical";
}

function readinessStatusTone(status: string): StatusTone {
  return status.toLowerCase() === "ready" ? "positive" : "warning";
}
