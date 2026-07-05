import { useQuery } from "@tanstack/react-query";
import Activity from "lucide-react/dist/esm/icons/activity.mjs";
import ServerCog from "lucide-react/dist/esm/icons/server-cog.mjs";
import { systemStatusQuery, type SystemStatusSnapshot } from "../api/system";
import { DataStrip } from "../ui/components/DataStrip";
import { EmptyState } from "../ui/components/EmptyState";
import { Metric } from "../ui/components/Metric";
import { Panel } from "../ui/components/Panel";
import { SectionHeader } from "../ui/components/SectionHeader";
import { StatusPill } from "../ui/components/StatusPill";
import { describeError, formatDateTime } from "../ui/format";

export function OverviewPage() {
  const statusQuery = useQuery(systemStatusQuery);

  return (
    <div className="page-stack">
      <SectionHeader
        eyebrow="App"
        title="Overview"
        description="Health, readiness, revision, and route surface."
      />

      {statusQuery.isPending ? <OverviewLoading /> : null}
      {statusQuery.isError ? <OverviewError error={statusQuery.error} /> : null}
      {statusQuery.data ? <OverviewSystemSummary data={statusQuery.data} /> : null}

      <div className="page-grid page-grid--two">
        <Panel>
          <div className="panel-heading">
            <ServerCog size={18} aria-hidden="true" />
            <h2>System routes</h2>
          </div>
          <DataStrip
            items={[
              {
                label: "Overview",
                value: "/app/overview",
                detail: "system summary",
              },
              {
                label: "Activity",
                value: "/app/activity",
                detail: "timeline surface",
              },
              {
                label: "System",
                value: "/app/system",
                detail: "endpoint responses",
              },
            ]}
          />
        </Panel>

        <Panel>
          <div className="panel-heading">
            <Activity size={18} aria-hidden="true" />
            <h2>Activity readiness</h2>
          </div>
          <EmptyState
            title="No activity records loaded"
            description="Execution and audit records are not present in this route yet."
          />
        </Panel>
      </div>
    </div>
  );
}

function OverviewSystemSummary({ data }: { data: SystemStatusSnapshot }) {
  const readinessTone = data.readiness.status.toLowerCase() === "ready" ? "positive" : "warning";

  return (
    <div className="metric-grid">
      <Metric label="Health" value={data.health.status} detail={data.health.service ?? "service not reported"} />
      <Metric
        label="Readiness"
        value={data.readiness.status}
        detail={`HTTP ${data.readinessHttpStatus}`}
      />
      <Metric label="Revision" value={data.revision} detail={`HTTP ${data.revisionHttpStatus}`} />
      <div className="metric">
        <div className="metric__label-row">
          <p className="metric__label">Snapshot</p>
          <StatusPill label={readinessTone === "positive" ? "ready" : "not ready"} tone={readinessTone} />
        </div>
        <p className="metric__value">Updated</p>
        <p className="metric__detail">{formatDateTime(data.fetchedAt)}</p>
      </div>
    </div>
  );
}

function OverviewLoading() {
  return (
    <Panel>
      <div className="loading-row" role="status">
        <span className="loading-dot" aria-hidden="true" />
        <span>Loading system status</span>
      </div>
    </Panel>
  );
}

function OverviewError({ error }: { error: unknown }) {
  return (
    <Panel>
      <EmptyState title="System status unavailable" description={describeError(error)} />
    </Panel>
  );
}
