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
import { DataStrip } from "../ui/components/DataStrip";
import { EmptyState } from "../ui/components/EmptyState";
import { Metric } from "../ui/components/Metric";
import { Panel } from "../ui/components/Panel";
import { SectionHeader } from "../ui/components/SectionHeader";
import { StatusPill, type StatusTone } from "../ui/components/StatusPill";
import { describeError, formatDateTime } from "../ui/format";
import { formatBtc, formatDecimal, formatJpy, formatRatioAsPercent, formatSignedJpy } from "../ui/numberFormat";

export function OverviewPage() {
  const statusQuery = useQuery(systemStatusQuery);
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
        description="Safety, risk, freshness, and paper account state."
        action={
          <button
            className="icon-text-button icon-text-button--prominent"
            type="button"
            onClick={refreshed}
            disabled={isRefreshing}
          >
            <RefreshCw size={16} aria-hidden="true" />
            {isRefreshing ? "Refreshing" : "Refresh"}
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

  return (
    <div className="metric-grid">
      <Metric
        label="System"
        value={queryValue(statusQuery, (data) => data.readiness.status)}
        detail={statusQuery.data ? `health ${statusQuery.data.health.status}` : queryDetail(statusQuery)}
      />
      <Metric
        label="Risk"
        value={queryValue(riskStateQuery, (data) => data.state)}
        detail={riskStateQuery.data ? formatRatioAsPercent(riskStateQuery.data.drawdownRatio) : queryDetail(riskStateQuery)}
      />
      <Metric
        label="Freshness"
        value={accountQuery.data ? formatDateTime(accountQuery.data.updatedAt) : queryValue(accountQuery, () => "Loading")}
        detail={accountQuery.data ? `account ${accountQuery.data.mode}` : queryDetail(accountQuery)}
      />
      <Metric
        label="Latest decision"
        value={latestDecision?.action ?? queryValue(decisionsQuery, () => "No records")}
        detail={latestDecision ? formatDateTime(latestDecision.createdAt) : queryDetail(decisionsQuery)}
      />
    </div>
  );
}

function SystemReadinessPanel({ statusQuery }: { statusQuery: UseQueryResult<SystemStatusSnapshot, Error> }) {
  if (statusQuery.isPending) {
    return <PanelLoading label="Loading system status" />;
  }

  if (statusQuery.isError) {
    return <PanelError title="System status unavailable" error={statusQuery.error} retried={() => void statusQuery.refetch()} />;
  }

  const readinessTone = readinessStatusTone(statusQuery.data.readiness.status);

  return (
    <Panel>
      <div className="panel-heading">
        <ServerCog size={18} aria-hidden="true" />
        <h2>System / readiness</h2>
        <StatusPill label={statusQuery.data.readiness.status} tone={readinessTone} />
      </div>
      <DataStrip
        items={[
          {
            label: "health",
            value: statusQuery.data.health.status,
            detail: statusQuery.data.health.service ?? "service not reported",
          },
          {
            label: "readiness",
            value: statusQuery.data.readiness.status,
            detail: `HTTP ${statusQuery.data.readinessHttpStatus}`,
          },
          {
            label: "revision",
            value: statusQuery.data.revision,
            detail: `HTTP ${statusQuery.data.revisionHttpStatus}`,
          },
          {
            label: "last reconciled",
            value: formatDateTime(statusQuery.data.readiness.lastReconciledAt),
          },
          {
            label: "last market data",
            value: formatDateTime(statusQuery.data.readiness.lastMarketDataAt),
          },
          {
            label: "snapshot",
            value: formatDateTime(statusQuery.data.fetchedAt),
            detail: statusQuery.isStale ? "stale" : "fresh",
          },
        ]}
      />
    </Panel>
  );
}

function RiskStatePanel({ riskStateQuery }: { riskStateQuery: UseQueryResult<OpsRiskStateResponse, Error> }) {
  if (riskStateQuery.isPending) {
    return <PanelLoading label="Loading risk state" />;
  }

  if (riskStateQuery.isError) {
    return <PanelError title="Risk state unavailable" error={riskStateQuery.error} retried={() => void riskStateQuery.refetch()} />;
  }

  return (
    <Panel>
      <div className="panel-heading">
        <ShieldAlert size={18} aria-hidden="true" />
        <h2>Risk state</h2>
        <StatusPill label={riskStateQuery.data.state} tone={riskStateTone(riskStateQuery.data.state)} />
      </div>
      <DataStrip
        items={[
          {
            label: "state",
            value: riskStateQuery.data.state,
            detail: riskStateQuery.data.haltReason ?? "no halt reason",
          },
          {
            label: "drawdown",
            value: formatRatioAsPercent(riskStateQuery.data.drawdownRatio),
          },
          {
            label: "halted at",
            value: formatDateTime(riskStateQuery.data.haltAt),
          },
          {
            label: "resumed at",
            value: formatDateTime(riskStateQuery.data.resumedAt),
            detail: riskStateQuery.data.resumedReason ?? "no resume reason",
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
  if (decisionsQuery.isPending) {
    return <PanelLoading label="Loading latest decision" />;
  }

  if (decisionsQuery.isError) {
    return <PanelError title="Decision feed unavailable" error={decisionsQuery.error} retried={() => void decisionsQuery.refetch()} />;
  }

  const latestDecision = decisionsQuery.data.decisions[0];

  if (!latestDecision) {
    return (
      <Panel>
        <div className="panel-heading">
          <Activity size={18} aria-hidden="true" />
          <h2>Latest decision</h2>
        </div>
        <EmptyState title="No decisions recorded" description="The decision repository returned an empty feed." />
      </Panel>
    );
  }

  return (
    <Panel>
      <div className="panel-heading">
        <Activity size={18} aria-hidden="true" />
        <h2>Latest decision</h2>
        <StatusPill label={latestDecision.action} tone={decisionTone(latestDecision.action)} />
      </div>
      <DataStrip
        items={[
          {
            label: "action",
            value: latestDecision.action,
            detail: formatDateTime(latestDecision.createdAt),
          },
          {
            label: "estimated p",
            value: formatRatioAsPercent(latestDecision.estimatedWinProbability),
          },
          {
            label: "setup tags",
            value: latestDecision.setupTags.length > 0 ? latestDecision.setupTags.join(", ") : "none",
          },
          {
            label: "reason",
            value: latestDecision.reasonJa,
          },
          {
            label: "no-trade conditions",
            value: latestDecision.noTradeConditionsJa.length > 0 ? latestDecision.noTradeConditionsJa.join(" / ") : "none",
          },
        ]}
      />
    </Panel>
  );
}

function AccountSnapshotPanel({ accountQuery }: { accountQuery: UseQueryResult<OpsAccountResponse, Error> }) {
  if (accountQuery.isPending) {
    return <PanelLoading label="Loading account snapshot" />;
  }

  if (accountQuery.isError) {
    return <PanelError title="Account snapshot unavailable" error={accountQuery.error} retried={() => void accountQuery.refetch()} />;
  }

  return (
    <Panel>
      <div className="panel-heading">
        <WalletCards size={18} aria-hidden="true" />
        <h2>Account snapshot</h2>
        <StatusPill label={accountQuery.data.mode} tone="neutral" />
      </div>
      <DataStrip
        items={[
          {
            label: "cash",
            value: formatJpy(accountQuery.data.cashJpy),
            detail: `initial ${formatJpy(accountQuery.data.initialCashJpy)}`,
          },
          {
            label: "total equity",
            value: formatJpy(accountQuery.data.totalEquityJpy),
            detail: `peak ${formatJpy(accountQuery.data.equityPeakJpy)}`,
          },
          {
            label: "drawdown",
            value: formatRatioAsPercent(accountQuery.data.drawdownRatio),
          },
          {
            label: "BTC quantity",
            value: formatBtc(accountQuery.data.btcQuantity),
          },
          {
            label: "BTC mark",
            value: formatJpy(accountQuery.data.btcMarkPriceJpy),
          },
          {
            label: "updated",
            value: formatDateTime(accountQuery.data.updatedAt),
          },
        ]}
      />
    </Panel>
  );
}

function PositionExposurePanel({ positionsQuery }: { positionsQuery: UseQueryResult<OpsPositionsResponse, Error> }) {
  if (positionsQuery.isPending) {
    return <PanelLoading label="Loading position exposure" />;
  }

  if (positionsQuery.isError) {
    return <PanelError title="Position feed unavailable" error={positionsQuery.error} retried={() => void positionsQuery.refetch()} />;
  }

  const hasExposure = positionsQuery.data.positions.length > 0 || positionsQuery.data.openOrders.length > 0;

  if (!hasExposure) {
    return (
      <Panel>
        <div className="panel-heading">
          <AlertTriangle size={18} aria-hidden="true" />
          <h2>Position / exposure</h2>
          <StatusPill label="flat" tone="positive" />
        </div>
        <EmptyState title="No open exposure" description="The paper ledger returned no open positions or open orders." />
      </Panel>
    );
  }

  return (
    <Panel>
      <div className="panel-heading">
        <AlertTriangle size={18} aria-hidden="true" />
        <h2>Position / exposure</h2>
        <StatusPill label="open" tone="warning" />
      </div>
      <DataStrip
        items={[
          {
            label: "positions",
            value: String(positionsQuery.data.positions.length),
          },
          {
            label: "open orders",
            value: String(positionsQuery.data.openOrders.length),
          },
          {
            label: "BTC size",
            value: formatBtc(sumNumbers(positionsQuery.data.positions.map((position) => position.sizeBtc))),
          },
          {
            label: "unrealized PnL",
            value: formatSignedJpy(sumNumbers(positionsQuery.data.positions.map((position) => position.unrealizedPnlJpy))),
          },
        ]}
      />
      <div className="compact-list" aria-label="Open positions">
        {positionsQuery.data.positions.map((position) => (
          <div className="compact-list__row" key={position.positionId}>
            <span>{position.symbol}</span>
            <span>{formatBtc(position.sizeBtc)}</span>
            <span>{formatSignedJpy(position.unrealizedPnlJpy)}</span>
            <span>stop {formatJpy(position.currentStopLossJpy)}</span>
          </div>
        ))}
      </div>
    </Panel>
  );
}

function KillCriterionPanel({
  evaluationQuery,
}: {
  evaluationQuery: UseQueryResult<EvaluationSummaryResponse, Error>;
}) {
  if (evaluationQuery.isPending) {
    return <PanelLoading label="Loading kill criterion" />;
  }

  if (evaluationQuery.isError) {
    return <PanelError title="Evaluation summary unavailable" error={evaluationQuery.error} retried={() => void evaluationQuery.refetch()} />;
  }

  const killCriterion = evaluationQuery.data.killCriterion;
  const killTone = killCriterion.hardHalt || killCriterion.breached ? "critical" : "positive";

  return (
    <Panel>
      <div className="panel-heading">
        <ShieldAlert size={18} aria-hidden="true" />
        <h2>Kill criterion</h2>
        <StatusPill label={killCriterion.breached ? "breached" : "within bounds"} tone={killTone} />
      </div>
      <DataStrip
        items={[
          {
            label: "closed trades",
            value: `${killCriterion.closedTrades} / ${killCriterion.minClosedTrades}`,
            detail: `${killCriterion.remainingTrades} remaining`,
          },
          {
            label: "profit factor",
            value: formatDecimal(killCriterion.currentProfitFactor),
            detail: `min ${formatDecimal(killCriterion.minProfitFactor)}`,
          },
          {
            label: "hard halt",
            value: killCriterion.hardHalt ? "yes" : "no",
          },
          {
            label: "total PnL",
            value: formatSignedJpy(evaluationQuery.data.performance.totalPnlJpy),
            detail: `${evaluationQuery.data.performance.tradeCount} trades`,
          },
          {
            label: "NO_TRADE rate",
            value: formatRatioAsPercent(evaluationQuery.data.runRates.noTradeRate),
          },
          {
            label: "period",
            value: `${evaluationQuery.data.period.from} to ${evaluationQuery.data.period.to}`,
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
  return (
    <Panel>
      <EmptyState
        title={title}
        description={describeError(error)}
        action={
          <button className="icon-text-button" type="button" onClick={retried}>
            <RefreshCw size={16} aria-hidden="true" />
            Retry
          </button>
        }
      />
    </Panel>
  );
}

function queryValue<TData>(
  query: UseQueryResult<TData, Error>,
  selected: (data: TData) => string,
): string {
  if (query.isPending) {
    return "Loading";
  }

  if (query.isError) {
    return "Error";
  }

  return selected(query.data);
}

function queryDetail<TData>(query: UseQueryResult<TData, Error>): string {
  if (query.isError) {
    return describeError(query.error);
  }

  return query.isPending ? "waiting for API" : "not reported";
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
