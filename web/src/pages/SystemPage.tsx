import { useQuery } from "@tanstack/react-query";
import AlertTriangle from "lucide-react/dist/esm/icons/alert-triangle.mjs";
import KeyRound from "lucide-react/dist/esm/icons/key-round.mjs";
import RefreshCw from "lucide-react/dist/esm/icons/refresh-cw.mjs";
import ServerCog from "lucide-react/dist/esm/icons/server-cog.mjs";
import { systemStatusQuery, type SystemStatusSnapshot } from "../api/system";
import { useI18n } from "../i18n/useI18n";
import { DataStrip } from "../ui/components/DataStrip";
import { EmptyState } from "../ui/components/EmptyState";
import { Metric } from "../ui/components/Metric";
import { Panel } from "../ui/components/Panel";
import { SectionHeader } from "../ui/components/SectionHeader";
import { StatusPill, type StatusTone } from "../ui/components/StatusPill";
import { describeError, formatDateTime } from "../ui/format";

export function SystemPage() {
  const statusQuery = useQuery(systemStatusQuery);
  const { t } = useI18n();

  return (
    <div className="page-stack">
      <SectionHeader
        eyebrow="App"
        title="System"
        description={t("system.description")}
        action={
          <button
            className="icon-text-button icon-text-button--prominent"
            type="button"
            onClick={() => void statusQuery.refetch()}
            disabled={statusQuery.isFetching}
          >
            <RefreshCw size={16} aria-hidden="true" />
            {statusQuery.isFetching ? t("common.refreshing") : t("common.refresh")}
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
  const { locale, t } = useI18n();
  const readinessTone = readinessStatusTone(data.readiness.status);
  const authSummary = llmAuthSummary(data);
  const marketDataState = data.readiness.marketDataState ?? "DISCONNECTED";

  return (
    <>
      <div className="metric-grid">
        <Metric
          label={t("system.metric.health")}
          value={data.health.status}
          detail={data.health.service ?? t("overview.detail.serviceNotReported")}
        />
        <Metric label={t("system.metric.readiness")} value={data.readiness.status} detail={`HTTP ${data.readinessHttpStatus}`} />
        <Metric label={t("system.metric.marketData")} value={marketDataState} detail={data.readiness.gapReason ?? t("common.notReported")} />
        <Metric label={t("system.metric.revision")} value={data.revision} detail={`HTTP ${data.revisionHttpStatus}`} />
        <Metric label={t("system.metric.llmAuth")} value={authSummary.value} detail={authSummary.detail} />
        <div className="metric">
          <div className="metric__label-row">
            <p className="metric__label">{t("system.metric.freshness")}</p>
            <StatusPill label={isStale ? t("common.stale") : t("common.fresh")} tone={isStale ? "warning" : "positive"} />
          </div>
          <p className="metric__value">{t("system.metric.updated")}</p>
          <p className="metric__detail">{formatDateTime(data.fetchedAt, locale)}</p>
        </div>
      </div>

      <Panel>
        <div className="panel-heading">
          <ServerCog size={18} aria-hidden="true" />
          <h2>{t("system.panel.endpointResponses")}</h2>
        </div>
        <div className="endpoint-table" role="table" aria-label={t("system.table.aria")}>
          <div className="endpoint-table__row endpoint-table__row--head" role="row">
            <span role="columnheader">{t("system.table.endpoint")}</span>
            <span role="columnheader">{t("system.table.http")}</span>
            <span role="columnheader">{t("system.table.payload")}</span>
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
          <h2>{t("system.panel.readinessTimestamps")}</h2>
          <StatusPill label={data.readiness.status} tone={readinessTone} />
        </div>
        <DataStrip
          items={[
            {
              label: t("system.label.lastReconciledAt"),
              value: formatDateTime(data.readiness.lastReconciledAt, locale),
            },
            {
              label: t("system.label.lastMarketDataAt"),
              value: formatDateTime(data.readiness.lastMarketDataAt, locale),
            },
            {
              label: t("system.label.gapStartedAt"),
              value: formatDateTime(data.readiness.gapStartedAt, locale),
              detail: data.readiness.gapReason ?? undefined,
            },
            {
              label: t("system.label.recoveredAt"),
              value: formatDateTime(data.readiness.recoveredAt, locale),
            },
          ]}
        />
      </Panel>

      <Panel>
        <div className="panel-heading">
          <KeyRound size={18} aria-hidden="true" />
          <h2>{t("system.panel.cliAuth")}</h2>
        </div>
        <DataStrip
          items={data.llmAuth.providers.map((provider) => ({
            label: provider.displayName,
            value: provider.status,
            detail: `${provider.detail ?? t("common.notReported")} / ${t("system.label.authHome")}: ${provider.homePath}`,
          }))}
        />
      </Panel>
    </>
  );
}

function SystemLoading() {
  const { t } = useI18n();

  return (
    <Panel>
      <div className="loading-row" role="status">
        <span className="loading-dot" aria-hidden="true" />
        <span>{t("system.loading.status")}</span>
      </div>
    </Panel>
  );
}

function SystemError({ error, retried }: { error: unknown; retried: () => void }) {
  const { locale, t } = useI18n();

  return (
    <Panel>
      <EmptyState
        title={t("system.error.dataUnavailable")}
        description={describeError(error, locale)}
        action={
          <button className="icon-text-button icon-text-button--prominent" type="button" onClick={retried}>
            <RefreshCw size={16} aria-hidden="true" />
            {t("common.retry")}
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
    case "/ops/llm-auth":
      return data.llmAuth.providers
        .map((provider) => `${provider.provider}=${provider.status}`)
        .join(", ");
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

function llmAuthSummary(data: SystemStatusSnapshot): { value: string; detail: string } {
  const loggedInCount = data.llmAuth.providers.filter((provider) => provider.status === "logged_in").length;
  const providerCount = data.llmAuth.providers.length;

  return {
    value: `${loggedInCount}/${providerCount} logged in`,
    detail: `HTTP ${data.llmAuthHttpStatus}`,
  };
}
