import { useQuery, type UseQueryResult } from "@tanstack/react-query";
import Activity from "lucide-react/dist/esm/icons/activity.mjs";
import AlertTriangle from "lucide-react/dist/esm/icons/alert-triangle.mjs";
import RefreshCw from "lucide-react/dist/esm/icons/refresh-cw.mjs";
import ServerCog from "lucide-react/dist/esm/icons/server-cog.mjs";
import ShieldAlert from "lucide-react/dist/esm/icons/shield-alert.mjs";
import WalletCards from "lucide-react/dist/esm/icons/wallet-cards.mjs";
import {
  evaluationSummaryQuery,
  opsAccountQuery,
  opsDecisionsQuery,
  opsPositionsQuery,
  opsRiskStateQuery,
  type EvaluationSummaryResponse,
  type OpsAccountResponse,
  type OpsPositionsResponse,
  type OpsRiskStateResponse,
} from "../api/ops";
import { systemStatusQuery, type SystemStatusSnapshot } from "../api/system";
import type { Locale, MessageKey } from "../i18n/messages";
import { useI18n } from "../i18n/useI18n";
import { DataStrip } from "../ui/components/DataStrip";
import { EmptyState } from "../ui/components/EmptyState";
import { Metric } from "../ui/components/Metric";
import { Panel } from "../ui/components/Panel";
import { SectionHeader } from "../ui/components/SectionHeader";
import { StatusPill, type StatusTone } from "../ui/components/StatusPill";
import { describeError, formatDateTime, formatTime } from "../ui/format";
import { formatBtc, formatDecimal, formatJpy, formatRatioAsPercent, formatSignedJpy } from "../ui/numberFormat";

export function OverviewPage() {
  const statusQuery = useQuery(systemStatusQuery);
  const { t } = useI18n();
  const riskStateQuery = useQuery(opsRiskStateQuery);
  const accountQuery = useQuery(opsAccountQuery);
  const decisionsQuery = useQuery(opsDecisionsQuery);
  const positionsQuery = useQuery(opsPositionsQuery);
  const evaluationQuery = useQuery(evaluationSummaryQuery);
  const queries = [statusQuery, riskStateQuery, accountQuery, decisionsQuery, positionsQuery, evaluationQuery];
  const isRefreshing = queries.some((query) => query.isFetching);
  const refreshed = () => {
    queries.forEach((query) => {
      void query.refetch();
    });
  };

  return (
    <div className="page-stack">
      <SectionHeader
        eyebrow="App"
        title="Overview"
        description={t("overview.description")}
        action={
          <button
            className="icon-text-button icon-text-button--prominent"
            type="button"
            onClick={refreshed}
            disabled={isRefreshing}
          >
            <RefreshCw size={16} aria-hidden="true" />
            {isRefreshing ? t("common.refreshing") : t("common.refresh")}
          </button>
        }
      />

      <OverviewSignalMetrics
        statusQuery={statusQuery}
        riskStateQuery={riskStateQuery}
        accountQuery={accountQuery}
        decisionsQuery={decisionsQuery}
      />

      <div className="page-grid page-grid--two">
        <SystemReadinessPanel statusQuery={statusQuery} />
        <RiskStatePanel riskStateQuery={riskStateQuery} />
        <LatestDecisionPanel decisionsQuery={decisionsQuery} />
        <AccountSnapshotPanel accountQuery={accountQuery} />
        <PositionExposurePanel positionsQuery={positionsQuery} />
        <KillCriterionPanel evaluationQuery={evaluationQuery} />
      </div>
    </div>
  );
}

function OverviewSignalMetrics({
  statusQuery,
  riskStateQuery,
  accountQuery,
  decisionsQuery,
}: {
  statusQuery: UseQueryResult<SystemStatusSnapshot, Error>;
  riskStateQuery: UseQueryResult<OpsRiskStateResponse, Error>;
  accountQuery: UseQueryResult<OpsAccountResponse, Error>;
  decisionsQuery: UseQueryResult<{ decisions: { action: string; createdAt: string }[] }, Error>;
}) {
  const latestDecision = decisionsQuery.data?.decisions[0];
  const { locale, t } = useI18n();

  return (
    <div className="metric-grid">
      <Metric
        label={t("overview.metric.system")}
        value={queryValue(statusQuery, (data) => data.readiness.status, t)}
        detail={statusQuery.data ? `${t("overview.detail.health")} ${statusQuery.data.health.status}` : queryDetail(statusQuery, locale, t)}
      />
      <Metric
        label={t("overview.metric.risk")}
        value={queryValue(riskStateQuery, (data) => data.state, t)}
        detail={riskStateQuery.data ? formatRatioAsPercent(riskStateQuery.data.drawdownRatio) : queryDetail(riskStateQuery, locale, t)}
      />
      <Metric
        label={t("overview.metric.freshness")}
        value={accountQuery.data ? formatDateTime(accountQuery.data.updatedAt, locale) : queryValue(accountQuery, () => t("common.loading"), t)}
        detail={accountQuery.data ? `${t("overview.detail.account")} ${accountQuery.data.mode}` : queryDetail(accountQuery, locale, t)}
      />
      <Metric
        label={t("overview.metric.latestDecision")}
        value={latestDecision?.action ?? queryValue(decisionsQuery, () => t("common.noRecords"), t)}
        detail={latestDecision ? formatDateTime(latestDecision.createdAt, locale) : queryDetail(decisionsQuery, locale, t)}
      />
    </div>
  );
}

function SystemReadinessPanel({ statusQuery }: { statusQuery: UseQueryResult<SystemStatusSnapshot, Error> }) {
  const { locale, t } = useI18n();

  if (statusQuery.isPending) {
    return <PanelLoading label={t("overview.loading.systemStatus")} />;
  }

  if (statusQuery.isError) {
    return <PanelError title={t("overview.error.systemStatus")} error={statusQuery.error} retried={() => void statusQuery.refetch()} />;
  }

  const readinessTone = readinessStatusTone(statusQuery.data.readiness.status);

  return (
    <Panel>
      <div className="panel-heading">
        <ServerCog size={18} aria-hidden="true" />
        <h2>{t("overview.panel.systemReadiness")}</h2>
        <StatusPill label={statusQuery.data.readiness.status} tone={readinessTone} />
      </div>
      <DataStrip
        items={[
          {
            label: t("overview.label.health"),
            value: statusQuery.data.health.status,
            detail: statusQuery.data.health.service ?? t("overview.detail.serviceNotReported"),
          },
          {
            label: t("overview.label.readiness"),
            value: statusQuery.data.readiness.status,
            detail: `HTTP ${statusQuery.data.readinessHttpStatus}`,
          },
          {
            label: t("overview.label.revision"),
            value: statusQuery.data.revision,
            detail: `HTTP ${statusQuery.data.revisionHttpStatus}`,
          },
          {
            label: t("system.label.lastTransportActivityAt"),
            value: formatDateTime(statusQuery.data.readiness.lastTransportActivityAt, locale),
          },
          {
            label: t("system.label.lastTradeAt"),
            value: formatDateTime(statusQuery.data.readiness.lastTradeAt, locale),
          },
          {
            label: t("system.label.lastMaintenanceAt"),
            value: formatDateTime(statusQuery.data.readiness.lastMaintenanceAt, locale),
          },
          {
            label: t("overview.label.snapshot"),
            value: formatDateTime(statusQuery.data.fetchedAt, locale),
            detail: statusQuery.isStale ? t("common.stale") : t("common.fresh"),
          },
        ]}
      />
    </Panel>
  );
}

function RiskStatePanel({ riskStateQuery }: { riskStateQuery: UseQueryResult<OpsRiskStateResponse, Error> }) {
  const { locale, t } = useI18n();

  if (riskStateQuery.isPending) {
    return <PanelLoading label={t("overview.loading.riskState")} />;
  }

  if (riskStateQuery.isError) {
    return <PanelError title={t("overview.error.riskState")} error={riskStateQuery.error} retried={() => void riskStateQuery.refetch()} />;
  }

  return (
    <Panel>
      <div className="panel-heading">
        <ShieldAlert size={18} aria-hidden="true" />
        <h2>{t("overview.panel.riskState")}</h2>
        <StatusPill label={riskStateQuery.data.state} tone={riskStateTone(riskStateQuery.data.state)} />
      </div>
      <DataStrip
        items={[
          {
            label: t("overview.label.state"),
            value: riskStateQuery.data.state,
            detail: riskStateQuery.data.haltReason ?? t("overview.detail.noHaltReason"),
          },
          {
            label: t("overview.label.drawdown"),
            value: formatRatioAsPercent(riskStateQuery.data.drawdownRatio),
          },
          {
            label: t("overview.label.haltedAt"),
            value: formatDateTime(riskStateQuery.data.haltAt, locale),
          },
          {
            label: t("overview.label.resumedAt"),
            value: formatDateTime(riskStateQuery.data.resumedAt, locale),
            detail: riskStateQuery.data.resumedReason ?? t("overview.detail.noResumeReason"),
          },
        ]}
      />
    </Panel>
  );
}

function LatestDecisionPanel({
  decisionsQuery,
}: {
  decisionsQuery: UseQueryResult<{ decisions: { action: string; createdAt: string; estimatedWinProbability: string; reasonJa: string; setupTags: string[]; noTradeConditionsJa: string[] }[] }, Error>;
}) {
  const { locale, t } = useI18n();

  if (decisionsQuery.isPending) {
    return <PanelLoading label={t("overview.loading.latestDecision")} />;
  }

  if (decisionsQuery.isError) {
    return <PanelError title={t("overview.error.decisionFeed")} error={decisionsQuery.error} retried={() => void decisionsQuery.refetch()} />;
  }

  const latestDecision = decisionsQuery.data.decisions[0];

  if (!latestDecision) {
    return (
      <Panel>
        <div className="panel-heading">
          <Activity size={18} aria-hidden="true" />
          <h2>{t("overview.panel.latestDecision")}</h2>
        </div>
        <EmptyState title={t("overview.empty.noDecisions.title")} description={t("overview.empty.noDecisions.description")} />
      </Panel>
    );
  }

  return (
    <Panel>
      <div className="panel-heading">
        <Activity size={18} aria-hidden="true" />
        <h2>{t("overview.panel.latestDecision")}</h2>
        <StatusPill label={latestDecision.action} tone={decisionTone(latestDecision.action)} />
      </div>
      <DataStrip
        items={[
          {
            label: t("overview.label.action"),
            value: latestDecision.action,
            detail: formatDateTime(latestDecision.createdAt, locale),
          },
          {
            label: t("overview.label.estimatedP"),
            value: formatRatioAsPercent(latestDecision.estimatedWinProbability),
          },
          {
            label: t("overview.label.setupTags"),
            value: latestDecision.setupTags.length > 0 ? latestDecision.setupTags.join(", ") : t("common.none"),
          },
          {
            label: t("overview.label.reason"),
            value: latestDecision.reasonJa,
          },
          {
            label: t("overview.label.noTradeConditions"),
            value: latestDecision.noTradeConditionsJa.length > 0 ? latestDecision.noTradeConditionsJa.join(" / ") : t("common.none"),
          },
        ]}
      />
    </Panel>
  );
}

function AccountSnapshotPanel({ accountQuery }: { accountQuery: UseQueryResult<OpsAccountResponse, Error> }) {
  const { locale, t } = useI18n();

  if (accountQuery.isPending) {
    return <PanelLoading label={t("overview.loading.accountSnapshot")} />;
  }

  if (accountQuery.isError) {
    return <PanelError title={t("overview.error.accountSnapshot")} error={accountQuery.error} retried={() => void accountQuery.refetch()} />;
  }

  return (
    <Panel>
      <div className="panel-heading">
        <WalletCards size={18} aria-hidden="true" />
        <h2>{t("overview.panel.accountSnapshot")}</h2>
        <StatusPill label={accountQuery.data.mode} tone="neutral" />
      </div>
      <DataStrip
        items={[
          {
            label: t("overview.label.cash"),
            value: formatJpy(accountQuery.data.cashJpy),
            detail: `${t("overview.detail.initial")} ${formatJpy(accountQuery.data.initialCashJpy)}`,
          },
          {
            label: t("overview.label.totalEquity"),
            value: formatJpy(accountQuery.data.totalEquityJpy),
            detail: `${t("overview.detail.peak")} ${formatJpy(accountQuery.data.equityPeakJpy)}`,
          },
          {
            label: t("overview.label.drawdown"),
            value: formatRatioAsPercent(accountQuery.data.drawdownRatio),
          },
          {
            label: t("overview.label.btcQuantity"),
            value: formatBtc(accountQuery.data.btcQuantity),
          },
          {
            label: t("overview.label.btcMark"),
            value: formatJpy(accountQuery.data.btcMarkPriceJpy),
          },
          {
            label: t("overview.label.updated"),
            value: formatDateTime(accountQuery.data.updatedAt, locale),
          },
        ]}
      />
    </Panel>
  );
}

function PositionExposurePanel({ positionsQuery }: { positionsQuery: UseQueryResult<OpsPositionsResponse, Error> }) {
  const { t } = useI18n();

  if (positionsQuery.isPending) {
    return <PanelLoading label={t("overview.loading.positionExposure")} />;
  }

  if (positionsQuery.isError) {
    return <PanelError title={t("overview.error.positionFeed")} error={positionsQuery.error} retried={() => void positionsQuery.refetch()} />;
  }

  const hasExposure = positionsQuery.data.positions.length > 0 || positionsQuery.data.openOrders.length > 0;
  const sellExecutionsByPositionId = new Map<string, OpsPositionsResponse["sellExecutions"]>();

  positionsQuery.data.sellExecutions.forEach((execution) => {
    if (!execution.positionId) {
      return;
    }

    sellExecutionsByPositionId.set(execution.positionId, [...(sellExecutionsByPositionId.get(execution.positionId) ?? []), execution]);
  });

  if (!hasExposure) {
    return (
      <Panel>
        <div className="panel-heading">
          <AlertTriangle size={18} aria-hidden="true" />
          <h2>{t("overview.panel.positionExposure")}</h2>
          <StatusPill label={t("overview.status.flat")} tone="positive" />
        </div>
        <EmptyState title={t("overview.empty.noExposure.title")} description={t("overview.empty.noExposure.description")} />
      </Panel>
    );
  }

  return (
    <Panel>
      <div className="panel-heading">
        <AlertTriangle size={18} aria-hidden="true" />
        <h2>{t("overview.panel.positionExposure")}</h2>
        <StatusPill label={t("overview.status.open")} tone="warning" />
      </div>
      <DataStrip
        items={[
          {
            label: t("overview.label.positions"),
            value: String(positionsQuery.data.positions.length),
          },
          {
            label: t("overview.label.openOrders"),
            value: String(positionsQuery.data.openOrders.length),
          },
          {
            label: t("overview.label.btcSize"),
            value: formatBtc(sumNumbers(positionsQuery.data.positions.map((position) => position.sizeBtc))),
          },
          {
            label: t("overview.label.unrealizedPnl"),
            value: formatSignedJpy(sumNumbers(positionsQuery.data.positions.map((position) => position.unrealizedPnlJpy))),
          },
        ]}
      />
      <div className="compact-list" aria-label="Open positions">
        {positionsQuery.data.positions.map((position) => {
          const sellExecutions = sellExecutionsByPositionId.get(position.positionId) ?? [];

          return (
            <div className="compact-list__row compact-list__row--positions" key={position.positionId}>
              <span>{position.symbol}</span>
              <span>
                {t("overview.detail.remaining")} {formatBtc(position.sizeBtc)}
              </span>
              <span>{formatSignedJpy(position.unrealizedPnlJpy)}</span>
              <span>
                {t("overview.detail.stop")} {formatJpy(position.currentStopLossJpy)}
              </span>
              <span>
                {sellExecutions.length === 0
                  ? t("overview.detail.noPartialCloses")
                  : sellExecutions
                      .map((execution) => `${formatBtc(execution.sizeBtc)} ${formatSignedJpy(execution.realizedPnlJpy)} ${formatTime(execution.executedAt)}`)
                      .join(" / ")}
              </span>
            </div>
          );
        })}
      </div>
    </Panel>
  );
}

function KillCriterionPanel({
  evaluationQuery,
}: {
  evaluationQuery: UseQueryResult<EvaluationSummaryResponse, Error>;
}) {
  const { t } = useI18n();

  if (evaluationQuery.isPending) {
    return <PanelLoading label={t("overview.loading.killCriterion")} />;
  }

  if (evaluationQuery.isError) {
    return <PanelError title={t("overview.error.evaluationSummary")} error={evaluationQuery.error} retried={() => void evaluationQuery.refetch()} />;
  }

  const killCriterion = evaluationQuery.data.killCriterion;
  const killTone = killCriterion.hardHalt || killCriterion.breached ? "critical" : "positive";

  return (
    <Panel>
      <div className="panel-heading">
        <ShieldAlert size={18} aria-hidden="true" />
        <h2>{t("overview.panel.killCriterion")}</h2>
        <StatusPill label={killCriterion.breached ? t("overview.status.breached") : t("overview.status.withinBounds")} tone={killTone} />
      </div>
      <DataStrip
        items={[
          {
            label: t("overview.label.closedTrades"),
            value: `${killCriterion.closedTrades} / ${killCriterion.minClosedTrades}`,
            detail: `${killCriterion.remainingTrades} ${t("overview.detail.remaining")}`,
          },
          {
            label: t("overview.label.profitFactor"),
            value: formatDecimal(killCriterion.currentProfitFactor),
            detail: `${t("overview.detail.min")} ${formatDecimal(killCriterion.minProfitFactor)}`,
          },
          {
            label: t("overview.label.hardHalt"),
            value: killCriterion.hardHalt ? t("common.yes") : t("common.no"),
          },
          {
            label: t("overview.label.totalPnl"),
            value: formatSignedJpy(evaluationQuery.data.performance.totalPnlJpy),
            detail: `${evaluationQuery.data.performance.tradeCount} ${t("overview.detail.trades")}`,
          },
          {
            label: t("overview.label.noTradeRate"),
            value: formatRatioAsPercent(evaluationQuery.data.runRates.noTradeRate),
          },
          {
            label: t("overview.label.period"),
            value: `${evaluationQuery.data.period.from} ${t("overview.detail.periodTo")} ${evaluationQuery.data.period.to}`,
            detail: evaluationQuery.data.period.timezone,
          },
        ]}
      />
    </Panel>
  );
}

function PanelLoading({ label }: { label: string }) {
  return (
    <Panel>
      <div className="loading-row" role="status">
        <span className="loading-dot" aria-hidden="true" />
        <span>{label}</span>
      </div>
    </Panel>
  );
}

function PanelError({ title, error, retried }: { title: string; error: unknown; retried: () => void }) {
  const { locale, t } = useI18n();

  return (
    <Panel>
      <EmptyState
        title={title}
        description={describeError(error, locale)}
        action={
          <button className="icon-text-button" type="button" onClick={retried}>
            <RefreshCw size={16} aria-hidden="true" />
            {t("common.retry")}
          </button>
        }
      />
    </Panel>
  );
}

function queryValue<TData>(
  query: UseQueryResult<TData, Error>,
  selected: (data: TData) => string,
  t: (key: MessageKey) => string,
): string {
  if (query.isPending) {
    return t("common.loading");
  }

  if (query.isError) {
    return t("common.error");
  }

  return selected(query.data);
}

function queryDetail<TData>(
  query: UseQueryResult<TData, Error>,
  locale: Locale,
  t: (key: MessageKey) => string,
): string {
  if (query.isError) {
    return describeError(query.error, locale);
  }

  return query.isPending ? t("common.waitingForApi") : t("common.notReported");
}

function readinessStatusTone(status: string): StatusTone {
  return status.toLowerCase() === "ready" ? "positive" : "warning";
}

function riskStateTone(state: string): StatusTone {
  if (state === "RUNNING") {
    return "positive";
  }

  if (state === "SOFT_HALT") {
    return "warning";
  }

  if (state === "HARD_HALT") {
    return "critical";
  }

  return "neutral";
}

function decisionTone(action: string): StatusTone {
  if (action === "NO_TRADE") {
    return "neutral";
  }

  if (action === "ENTER") {
    return "warning";
  }

  return "positive";
}

function sumNumbers(values: string[]): string | null {
  let total = 0;

  for (const value of values) {
    const parsedValue = Number(value);

    if (!Number.isFinite(parsedValue)) {
      return null;
    }

    total += parsedValue;
  }

  return String(total);
}
