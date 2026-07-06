import { useQuery, type UseQueryResult } from "@tanstack/react-query";
import type { LucideIcon } from "lucide-react";
import type { ReactNode } from "react";
import Activity from "lucide-react/dist/esm/icons/activity.mjs";
import AlertTriangle from "lucide-react/dist/esm/icons/alert-triangle.mjs";
import RefreshCw from "lucide-react/dist/esm/icons/refresh-cw.mjs";
import ShieldAlert from "lucide-react/dist/esm/icons/shield-alert.mjs";
import WalletCards from "lucide-react/dist/esm/icons/wallet-cards.mjs";
import {
  evaluationBenchmarkQuery,
  evaluationCalibrationQuery,
  evaluationCostsQuery,
  evaluationSetupsQuery,
  evaluationSummaryQuery,
  type EvaluationBenchmarkResponse,
  type EvaluationCalibrationResponse,
  type EvaluationCostsResponse,
  type EvaluationSetupsResponse,
  type EvaluationSummaryResponse,
} from "../api/ops";
import { DataStrip } from "../ui/components/DataStrip";
import { EmptyState } from "../ui/components/EmptyState";
import { Metric } from "../ui/components/Metric";
import { Panel } from "../ui/components/Panel";
import { SectionHeader } from "../ui/components/SectionHeader";
import { StatusPill, type StatusTone } from "../ui/components/StatusPill";
import { describeError } from "../ui/format";
import { formatDecimal, formatInteger, formatJpy, formatRatioAsPercent, formatSignedJpy, formatUsd } from "../ui/numberFormat";

type Performance = EvaluationSummaryResponse["performance"];
type MarketRegime = EvaluationSummaryResponse["marketRegimes"][number];
type CalibrationGroup = EvaluationCalibrationResponse["bySetup"][number];
type CalibrationBin = CalibrationGroup["bins"][number];
type BenchmarkPoint = EvaluationBenchmarkResponse["points"][number];

export function EvaluationPage() {
  const summaryQuery = useQuery(evaluationSummaryQuery);
  const setupsQuery = useQuery(evaluationSetupsQuery);
  const calibrationQuery = useQuery(evaluationCalibrationQuery);
  const benchmarkQuery = useQuery(evaluationBenchmarkQuery);
  const costsQuery = useQuery(evaluationCostsQuery);
  const queries = [summaryQuery, setupsQuery, calibrationQuery, benchmarkQuery, costsQuery];
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
        title="Evaluation"
        description="Model quality, paper-trading performance, calibration, benchmark, kill criterion, and LLM cost."
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

      <EvaluationSignalMetrics summaryQuery={summaryQuery} benchmarkQuery={benchmarkQuery} costsQuery={costsQuery} />

      <div className="page-grid page-grid--two">
        <EvaluationSummaryPanel summaryQuery={summaryQuery} />
        <KillCriterionPanel summaryQuery={summaryQuery} />
        <SetupPerformancePanel setupsQuery={setupsQuery} />
        <BenchmarkPanel benchmarkQuery={benchmarkQuery} />
        <CalibrationPanel calibrationQuery={calibrationQuery} />
        <CostsPanel costsQuery={costsQuery} />
      </div>
    </div>
  );
}

function EvaluationSignalMetrics({
  summaryQuery,
  benchmarkQuery,
  costsQuery,
}: {
  summaryQuery: UseQueryResult<EvaluationSummaryResponse, Error>;
  benchmarkQuery: UseQueryResult<EvaluationBenchmarkResponse, Error>;
  costsQuery: UseQueryResult<EvaluationCostsResponse, Error>;
}) {
  return (
    <div className="metric-grid">
      <Metric
        label="Profit factor"
        value={queryValue(summaryQuery, (data) => formatDecimal(data.performance.profitFactor))}
        detail={summaryQuery.data ? `${formatInteger(summaryQuery.data.performance.tradeCount)} closed trades` : queryDetail(summaryQuery)}
      />
      <Metric
        label="Win rate"
        value={queryValue(summaryQuery, (data) => formatRatioAsPercent(data.performance.winRate))}
        detail={summaryQuery.data ? `expected R ${formatDecimal(summaryQuery.data.performance.expectedR)}` : queryDetail(summaryQuery)}
      />
      <Metric
        label="Bot drawdown"
        value={queryValue(benchmarkQuery, (data) => formatRatioAsPercent(calculateWorstBotDrawdown(data.points)))}
        detail={benchmarkQuery.data ? `${formatInteger(benchmarkQuery.data.points.length)} benchmark points` : queryDetail(benchmarkQuery)}
      />
      <Metric
        label="LLM cost"
        value={queryValue(costsQuery, (data) => formatUsd(data.totalCostUsd))}
        detail={costsQuery.data ? `${formatInteger(costsQuery.data.phaseCount)} phases` : queryDetail(costsQuery)}
      />
    </div>
  );
}

function EvaluationSummaryPanel({
  summaryQuery,
}: {
  summaryQuery: UseQueryResult<EvaluationSummaryResponse, Error>;
}) {
  if (summaryQuery.isPending) {
    return <PanelLoading title="Evaluation summary" label="Loading evaluation summary" Icon={Activity} />;
  }

  if (summaryQuery.isError) {
    return <PanelError title="Evaluation summary unavailable" error={summaryQuery.error} retried={() => void summaryQuery.refetch()} />;
  }

  const { performance, runRates, period } = summaryQuery.data;

  return (
    <Panel>
      <PanelHeading Icon={Activity} title="Evaluation summary">
        <FreshnessPill isStale={summaryQuery.isStale} />
        <TruncationPill truncated={summaryQuery.data.truncated} />
      </PanelHeading>
      <DataStrip
        items={[
          {
            label: "period",
            value: periodLabel(period),
            detail: period.timezone,
          },
          {
            label: "total PnL",
            value: formatSignedJpy(performance.totalPnlJpy),
            detail: `${formatInteger(performance.tradeCount)} trades`,
          },
          {
            label: "profit factor",
            value: formatDecimal(performance.profitFactor),
            detail: `win ${formatRatioAsPercent(performance.winRate)}`,
          },
          {
            label: "expected R",
            value: formatDecimal(performance.expectedR),
            detail: `missing R ${formatInteger(performance.rUnavailableCount)}`,
          },
          {
            label: "MAE / MFE",
            value: `${formatDecimal(performance.averageMaeR)} / ${formatDecimal(performance.averageMfeR)}`,
            detail: `missing ${formatInteger(performance.maeUnavailableCount)} / ${formatInteger(performance.mfeUnavailableCount)}`,
          },
          {
            label: "decision runs",
            value: formatInteger(runRates.decisionRunCount),
            detail: `entry ${formatRatioAsPercent(runRates.entryRate)} / no-trade ${formatRatioAsPercent(runRates.noTradeRate)}`,
          },
        ]}
      />
      <ActionCounts actionCounts={runRates.actionCounts} />
      <MarketRegimeTable regimes={summaryQuery.data.marketRegimes} />
    </Panel>
  );
}

function ActionCounts({ actionCounts }: { actionCounts: EvaluationSummaryResponse["runRates"]["actionCounts"] }) {
  if (actionCounts.length === 0) {
    return <EmptyState title="No action counts" description="The evaluation summary did not return decision action counts." />;
  }

  return (
    <div className="evaluation-action-grid" aria-label="Decision action counts">
      {actionCounts.map((actionCount) => (
        <div className="evaluation-action-grid__item" key={actionCount.action}>
          <span>{actionCount.action}</span>
          <strong>{formatInteger(actionCount.count)}</strong>
        </div>
      ))}
    </div>
  );
}

function MarketRegimeTable({ regimes }: { regimes: MarketRegime[] }) {
  if (regimes.length === 0) {
    return <p className="evaluation-note">No market regime slices reported for this period.</p>;
  }

  return (
    <div className="evaluation-subsection">
      <h3>Market regimes</h3>
      <div className="evaluation-table evaluation-table--regimes" role="table" aria-label="Market regime performance">
        <div className="evaluation-table__row evaluation-table__row--head" role="row">
          <span role="columnheader">Regime</span>
          <span role="columnheader">Trades</span>
          <span role="columnheader">PnL</span>
          <span role="columnheader">PF</span>
          <span role="columnheader">Win</span>
        </div>
        {regimes.map((regime) => (
          <div className="evaluation-table__row" role="row" key={`${regime.trend}:${regime.volatility}`}>
            <span role="cell">{`${regime.trend} / ${regime.volatility}`}</span>
            <span role="cell">{formatInteger(regime.performance.tradeCount)}</span>
            <span role="cell">{formatSignedJpy(regime.performance.totalPnlJpy)}</span>
            <span role="cell">{formatDecimal(regime.performance.profitFactor)}</span>
            <span role="cell">{formatRatioAsPercent(regime.performance.winRate)}</span>
          </div>
        ))}
      </div>
    </div>
  );
}

function KillCriterionPanel({
  summaryQuery,
}: {
  summaryQuery: UseQueryResult<EvaluationSummaryResponse, Error>;
}) {
  if (summaryQuery.isPending) {
    return <PanelLoading title="Kill criterion" label="Loading kill criterion" Icon={ShieldAlert} />;
  }

  if (summaryQuery.isError) {
    return <PanelError title="Kill criterion unavailable" error={summaryQuery.error} retried={() => void summaryQuery.refetch()} />;
  }

  const killCriterion = summaryQuery.data.killCriterion;
  const progressRatio = killCriterion.minClosedTrades === 0 ? 1 : killCriterion.closedTrades / killCriterion.minClosedTrades;

  return (
    <Panel>
      <PanelHeading Icon={ShieldAlert} title="Kill criterion">
        <StatusPill label={killCriterionLabel(killCriterion)} tone={killCriterionTone(killCriterion)} />
        <FreshnessPill isStale={summaryQuery.isStale} />
      </PanelHeading>
      <DataStrip
        items={[
          {
            label: "closed trades",
            value: `${formatInteger(killCriterion.closedTrades)} / ${formatInteger(killCriterion.minClosedTrades)}`,
            detail: `${formatInteger(killCriterion.remainingTrades)} remaining`,
          },
          {
            label: "profit factor",
            value: formatDecimal(killCriterion.currentProfitFactor),
            detail: `floor ${formatDecimal(killCriterion.minProfitFactor)}`,
          },
          {
            label: "breached",
            value: killCriterion.breached ? "yes" : "no",
          },
          {
            label: "hard halt",
            value: killCriterion.hardHalt ? "yes" : "no",
          },
          {
            label: "total PnL",
            value: formatSignedJpy(summaryQuery.data.performance.totalPnlJpy),
          },
          {
            label: "NO_TRADE rate",
            value: formatRatioAsPercent(summaryQuery.data.runRates.noTradeRate),
          },
        ]}
      />
      <div className="evaluation-progress" aria-label="Kill criterion trade progress">
        <div className="evaluation-progress__track">
          <span className="evaluation-progress__bar" style={{ width: ratioWidth(progressRatio) }} />
        </div>
        <span>
          {formatInteger(killCriterion.closedTrades)} closed / {formatInteger(killCriterion.minClosedTrades)} required before PF floor is decisive
        </span>
      </div>
    </Panel>
  );
}

function SetupPerformancePanel({
  setupsQuery,
}: {
  setupsQuery: UseQueryResult<EvaluationSetupsResponse, Error>;
}) {
  if (setupsQuery.isPending) {
    return <PanelLoading title="Setup performance" label="Loading setup performance" Icon={AlertTriangle} isWide />;
  }

  if (setupsQuery.isError) {
    return <PanelError title="Setup performance unavailable" error={setupsQuery.error} retried={() => void setupsQuery.refetch()} isWide />;
  }

  return (
    <Panel className="panel--wide">
      <PanelHeading Icon={AlertTriangle} title="Setup performance">
        <FreshnessPill isStale={setupsQuery.isStale} />
        <TruncationPill truncated={setupsQuery.data.truncated} />
      </PanelHeading>
      <p className="evaluation-note">
        {periodLabel(setupsQuery.data.period)} / {setupsQuery.data.period.timezone}
      </p>
      {setupsQuery.data.setups.length === 0 ? (
        <EmptyState title="No setup trades" description="The evaluation API returned no setup-level closed trade rows." />
      ) : (
        <div className="evaluation-table evaluation-table--setups" role="table" aria-label="Setup performance">
          <div className="evaluation-table__row evaluation-table__row--head" role="row">
            <span role="columnheader">Setup</span>
            <span role="columnheader">Trades</span>
            <span role="columnheader">PnL</span>
            <span role="columnheader">PF</span>
            <span role="columnheader">Win</span>
            <span role="columnheader">Expected R</span>
            <span role="columnheader">MAE / MFE</span>
            <span role="columnheader">Missing</span>
          </div>
          {setupsQuery.data.setups.map((setup) => (
            <div className="evaluation-table__row" role="row" key={setup.setupTag}>
              <span role="cell">{setup.setupTag}</span>
              <span role="cell">{formatInteger(setup.performance.tradeCount)}</span>
              <span role="cell">{formatSignedJpy(setup.performance.totalPnlJpy)}</span>
              <span role="cell">
                <StatusPill label={formatDecimal(setup.performance.profitFactor)} tone={profitFactorTone(setup.performance.profitFactor)} />
              </span>
              <span role="cell">{formatRatioAsPercent(setup.performance.winRate)}</span>
              <span role="cell">{formatDecimal(setup.performance.expectedR)}</span>
              <span role="cell">{`${formatDecimal(setup.performance.averageMaeR)} / ${formatDecimal(setup.performance.averageMfeR)}`}</span>
              <span role="cell">{missingPerformanceLabel(setup.performance)}</span>
            </div>
          ))}
        </div>
      )}
      <MarketRegimeTable regimes={setupsQuery.data.marketRegimes} />
    </Panel>
  );
}

function CalibrationPanel({
  calibrationQuery,
}: {
  calibrationQuery: UseQueryResult<EvaluationCalibrationResponse, Error>;
}) {
  if (calibrationQuery.isPending) {
    return <PanelLoading title="Calibration" label="Loading calibration bins" Icon={Activity} isWide />;
  }

  if (calibrationQuery.isError) {
    return <PanelError title="Calibration unavailable" error={calibrationQuery.error} retried={() => void calibrationQuery.refetch()} isWide />;
  }

  const hasGroups = calibrationQuery.data.bySetup.length > 0 || calibrationQuery.data.byProvider.length > 0;

  return (
    <Panel className="panel--wide">
      <PanelHeading Icon={Activity} title="Calibration">
        <FreshnessPill isStale={calibrationQuery.isStale} />
        <TruncationPill truncated={calibrationQuery.data.truncated} />
      </PanelHeading>
      <p className="evaluation-note">
        {periodLabel(calibrationQuery.data.period)} / {calibrationQuery.data.period.timezone}
      </p>
      {hasGroups ? (
        <div className="calibration-layout">
          <CalibrationGroupList title="By setup" groups={calibrationQuery.data.bySetup} />
          <CalibrationGroupList title="By provider" groups={calibrationQuery.data.byProvider} />
        </div>
      ) : (
        <EmptyState title="No calibration bins" description="Closed ENTER decisions did not produce setup or provider calibration bins." />
      )}
    </Panel>
  );
}

function CalibrationGroupList({ title, groups }: { title: string; groups: CalibrationGroup[] }) {
  if (groups.length === 0) {
    return (
      <div className="calibration-groups">
        <h3>{title}</h3>
        <EmptyState title="No groups" description="This calibration dimension has no bins for the selected period." />
      </div>
    );
  }

  return (
    <div className="calibration-groups">
      <h3>{title}</h3>
      {groups.map((group) => (
        <div className="calibration-group" key={`${title}:${group.groupKey}`}>
          <div className="calibration-group__heading">
            <strong>{group.groupKey}</strong>
            <span>{formatInteger(sumBinTrades(group.bins))} trades</span>
          </div>
          <div className="calibration-bin-list">
            {group.bins.map((bin) => (
              <CalibrationBinRow bin={bin} key={bin.binIndex} />
            ))}
          </div>
        </div>
      ))}
    </div>
  );
}

function CalibrationBinRow({ bin }: { bin: CalibrationBin }) {
  return (
    <div className="calibration-bin">
      <div className="calibration-bin__label">
        <span>{`${formatRatioAsPercent(bin.lowerBoundInclusive)}-${formatRatioAsPercent(bin.upperBoundInclusive)}`}</span>
        <span>{formatInteger(bin.tradeCount)} trades</span>
      </div>
      <div className="calibration-bin__bars" aria-hidden="true">
        <span className="calibration-bin__bar calibration-bin__bar--estimated" style={{ width: probabilityWidth(bin.averageEstimatedProbability) }} />
        <span className="calibration-bin__bar calibration-bin__bar--realized" style={{ width: probabilityWidth(bin.realizedWinRate) }} />
      </div>
      <div className="calibration-bin__values">
        <span>est {formatRatioAsPercent(bin.averageEstimatedProbability)}</span>
        <span>real {formatRatioAsPercent(bin.realizedWinRate)}</span>
      </div>
    </div>
  );
}

function BenchmarkPanel({
  benchmarkQuery,
}: {
  benchmarkQuery: UseQueryResult<EvaluationBenchmarkResponse, Error>;
}) {
  if (benchmarkQuery.isPending) {
    return <PanelLoading title="Benchmark comparison" label="Loading benchmark comparison" Icon={Activity} isWide />;
  }

  if (benchmarkQuery.isError) {
    return <PanelError title="Benchmark unavailable" error={benchmarkQuery.error} retried={() => void benchmarkQuery.refetch()} isWide />;
  }

  const benchmarkRows = benchmarkReturnRows(benchmarkQuery.data);
  const latestPoints = latestBenchmarkPoints(benchmarkQuery.data.points);

  return (
    <Panel className="panel--wide">
      <PanelHeading Icon={Activity} title="Benchmark comparison">
        <FreshnessPill isStale={benchmarkQuery.isStale} />
        {benchmarkQuery.data.points.length > latestPoints.length ? (
          <StatusPill label={`latest ${latestPoints.length} of ${benchmarkQuery.data.points.length}`} tone="warning" />
        ) : null}
      </PanelHeading>
      <DataStrip
        items={[
          {
            label: "period",
            value: periodLabel(benchmarkQuery.data.period),
            detail: benchmarkQuery.data.period.timezone,
          },
          {
            label: "baseline",
            value: formatJpy(benchmarkQuery.data.baselineEquityJpy),
          },
          {
            label: "bot return",
            value: formatRatioAsPercent(benchmarkQuery.data.returns.botReturn),
          },
          {
            label: "buy & hold",
            value: formatRatioAsPercent(benchmarkQuery.data.returns.buyAndHoldReturn),
          },
          {
            label: "no-trade",
            value: formatRatioAsPercent(benchmarkQuery.data.returns.noTradeReturn),
          },
          {
            label: "bot drawdown",
            value: formatRatioAsPercent(calculateWorstBotDrawdown(benchmarkQuery.data.points)),
          },
        ]}
      />
      <p className="evaluation-note">{benchmarkQuery.data.assumptionsJa}</p>
      <div className="benchmark-return-list" aria-label="Benchmark returns">
        {benchmarkRows.map((row) => (
          <div className="benchmark-return" key={row.label}>
            <span>{row.label}</span>
            <div className="benchmark-return__track" aria-hidden="true">
              <span className={`benchmark-return__bar benchmark-return__bar--${row.tone}`} style={{ width: row.width }} />
            </div>
            <strong>{formatRatioAsPercent(row.value)}</strong>
          </div>
        ))}
      </div>
      {latestPoints.length === 0 ? (
        <EmptyState title="No benchmark series" description="The benchmark API returned no daily equity points." />
      ) : (
        <div className="evaluation-table evaluation-table--benchmark" role="table" aria-label="Latest benchmark equity points">
          <div className="evaluation-table__row evaluation-table__row--head" role="row">
            <span role="columnheader">Date</span>
            <span role="columnheader">Bot</span>
            <span role="columnheader">Buy & hold</span>
            <span role="columnheader">No-trade</span>
          </div>
          {latestPoints.map((point) => (
            <div className="evaluation-table__row" role="row" key={point.date}>
              <span role="cell">{point.date}</span>
              <span role="cell">{formatJpy(point.botEquityJpy)}</span>
              <span role="cell">{formatJpy(point.buyAndHoldEquityJpy)}</span>
              <span role="cell">{formatJpy(point.noTradeEquityJpy)}</span>
            </div>
          ))}
        </div>
      )}
    </Panel>
  );
}

function CostsPanel({
  costsQuery,
}: {
  costsQuery: UseQueryResult<EvaluationCostsResponse, Error>;
}) {
  if (costsQuery.isPending) {
    return <PanelLoading title="LLM cost / usage" label="Loading LLM cost and usage" Icon={WalletCards} isWide />;
  }

  if (costsQuery.isError) {
    return <PanelError title="LLM cost unavailable" error={costsQuery.error} retried={() => void costsQuery.refetch()} isWide />;
  }

  return (
    <Panel className="panel--wide">
      <PanelHeading Icon={WalletCards} title="LLM cost / usage">
        <FreshnessPill isStale={costsQuery.isStale} />
        <TruncationPill truncated={costsQuery.data.truncated} />
      </PanelHeading>
      <DataStrip
        items={[
          {
            label: "period",
            value: periodLabel(costsQuery.data.period),
            detail: costsQuery.data.period.timezone,
          },
          {
            label: "total cost",
            value: formatUsd(costsQuery.data.totalCostUsd),
          },
          {
            label: "phases",
            value: formatInteger(costsQuery.data.phaseCount),
            detail: `${formatInteger(costsQuery.data.missingUsagePhaseCount)} missing usage`,
          },
        ]}
      />
      <ProviderCostTable costs={costsQuery.data.byProvider} />
      <ModelTokenTable models={costsQuery.data.byModel} />
    </Panel>
  );
}

function ProviderCostTable({ costs }: { costs: EvaluationCostsResponse["byProvider"] }) {
  if (costs.length === 0) {
    return <EmptyState title="No provider usage" description="No provider-level LLM usage rows were returned." />;
  }

  return (
    <div className="evaluation-subsection">
      <h3>Provider cost</h3>
      <div className="evaluation-table evaluation-table--costs" role="table" aria-label="Provider costs">
        <div className="evaluation-table__row evaluation-table__row--head" role="row">
          <span role="columnheader">Provider</span>
          <span role="columnheader">Cost</span>
          <span role="columnheader">Phases</span>
          <span role="columnheader">Missing usage</span>
        </div>
        {costs.map((cost) => (
          <div className="evaluation-table__row" role="row" key={cost.provider}>
            <span role="cell">{cost.provider}</span>
            <span role="cell">{formatUsd(cost.totalCostUsd)}</span>
            <span role="cell">{formatInteger(cost.phaseCount)}</span>
            <span role="cell">{formatInteger(cost.missingUsagePhaseCount)}</span>
          </div>
        ))}
      </div>
    </div>
  );
}

function ModelTokenTable({ models }: { models: EvaluationCostsResponse["byModel"] }) {
  if (models.length === 0) {
    return <EmptyState title="No model tokens" description="No model-level token usage rows were returned." />;
  }

  return (
    <div className="evaluation-subsection">
      <h3>Model tokens</h3>
      <div className="evaluation-table evaluation-table--models" role="table" aria-label="Model token usage">
        <div className="evaluation-table__row evaluation-table__row--head" role="row">
          <span role="columnheader">Model</span>
          <span role="columnheader">Input</span>
          <span role="columnheader">Output</span>
          <span role="columnheader">Cache create</span>
          <span role="columnheader">Cache read</span>
        </div>
        {models.map((model) => (
          <div className="evaluation-table__row" role="row" key={model.model}>
            <span role="cell">{model.model}</span>
            <span role="cell">{formatInteger(model.inputTokens)}</span>
            <span role="cell">{formatInteger(model.outputTokens)}</span>
            <span role="cell">{formatInteger(model.cacheCreationInputTokens)}</span>
            <span role="cell">{formatInteger(model.cacheReadInputTokens)}</span>
          </div>
        ))}
      </div>
    </div>
  );
}

function PanelHeading({ Icon, title, children }: { Icon: LucideIcon; title: string; children?: ReactNode }) {
  return (
    <div className="panel-heading">
      <Icon size={18} aria-hidden="true" />
      <h2>{title}</h2>
      {children}
    </div>
  );
}

function PanelLoading({ title, label, Icon, isWide = false }: { title: string; label: string; Icon: LucideIcon; isWide?: boolean }) {
  return (
    <Panel className={isWide ? "panel--wide" : undefined}>
      <PanelHeading Icon={Icon} title={title}>
        <StatusPill label="loading" tone="loading" />
      </PanelHeading>
      <div className="loading-row" role="status">
        <span className="loading-dot" aria-hidden="true" />
        <span>{label}</span>
      </div>
    </Panel>
  );
}

function PanelError({ title, error, retried, isWide = false }: { title: string; error: unknown; retried: () => void; isWide?: boolean }) {
  return (
    <Panel className={isWide ? "panel--wide" : undefined}>
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

function FreshnessPill({ isStale }: { isStale: boolean }) {
  return <StatusPill label={isStale ? "stale" : "fresh"} tone={isStale ? "warning" : "positive"} />;
}

function TruncationPill({ truncated }: { truncated: boolean }) {
  return truncated ? <StatusPill label="truncated" tone="warning" /> : <StatusPill label="complete" tone="neutral" />;
}

function queryValue<TData>(query: UseQueryResult<TData, Error>, selected: (data: TData) => string): string {
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

function periodLabel(period: EvaluationSummaryResponse["period"]): string {
  return `${period.from} to ${period.to}`;
}

function missingPerformanceLabel(performance: Performance): string {
  return `R ${formatInteger(performance.rUnavailableCount)} / MAE ${formatInteger(performance.maeUnavailableCount)} / MFE ${formatInteger(performance.mfeUnavailableCount)}`;
}

function killCriterionLabel(killCriterion: EvaluationSummaryResponse["killCriterion"]): string {
  if (killCriterion.hardHalt) {
    return "hard halt";
  }

  return killCriterion.breached ? "breached" : "within bounds";
}

function killCriterionTone(killCriterion: EvaluationSummaryResponse["killCriterion"]): StatusTone {
  return killCriterion.hardHalt || killCriterion.breached ? "critical" : "positive";
}

function profitFactorTone(value: string | null | undefined): StatusTone {
  const number = numericValue(value);

  if (number === null) {
    return "neutral";
  }

  if (number >= 1.2) {
    return "positive";
  }

  return number >= 1 ? "warning" : "critical";
}

function ratioWidth(value: number): string {
  return `${Math.min(Math.max(value, 0), 1) * 100}%`;
}

function probabilityWidth(value: string | null | undefined): string {
  const number = numericValue(value);

  return ratioWidth(number ?? 0);
}

function sumBinTrades(bins: CalibrationBin[]): number {
  return bins.reduce((total, bin) => total + bin.tradeCount, 0);
}

function benchmarkReturnRows(data: EvaluationBenchmarkResponse) {
  const rows = [
    {
      label: "Bot realized",
      value: data.returns.botReturn,
    },
    {
      label: "Buy & hold",
      value: data.returns.buyAndHoldReturn,
    },
    {
      label: "No-trade",
      value: data.returns.noTradeReturn,
    },
  ];
  const maxAbsReturn = Math.max(...rows.map((row) => Math.abs(numericValue(row.value) ?? 0)), 0.01);

  return rows.map((row) => {
    const numericReturn = numericValue(row.value);
    const width = numericReturn === null ? "0%" : ratioWidth(Math.abs(numericReturn) / maxAbsReturn);

    return {
      ...row,
      width,
      tone: numericReturn === null ? "neutral" : numericReturn >= 0 ? "positive" : "critical",
    };
  });
}

function latestBenchmarkPoints(points: BenchmarkPoint[]): BenchmarkPoint[] {
  return points.slice(Math.max(points.length - 6, 0));
}

function calculateWorstBotDrawdown(points: BenchmarkPoint[]): string | null {
  let peak: number | null = null;
  let worstDrawdown = 0;

  for (const point of points) {
    const equity = numericValue(point.botEquityJpy);

    if (equity === null) {
      continue;
    }

    if (peak === null || equity > peak) {
      peak = equity;
    }

    if (peak > 0) {
      worstDrawdown = Math.max(worstDrawdown, (peak - equity) / peak);
    }
  }

  return peak === null ? null : String(worstDrawdown);
}

function numericValue(value: string | null | undefined): number | null {
  if (value === null || value === undefined || value.trim() === "") {
    return null;
  }

  const number = Number(value);

  return Number.isFinite(number) ? number : null;
}
