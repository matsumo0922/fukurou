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
import type { MessageKey } from "../i18n/messages";
import { useI18n } from "../i18n/useI18n";
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
type Translate = (key: MessageKey) => string;

export function EvaluationPage() {
  const { t } = useI18n();
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
        description={t("evaluation.description")}
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
  const { t } = useI18n();

  return (
    <div className="metric-grid">
      <Metric
        label={t("evaluation.metric.profitFactor")}
        value={queryValue(summaryQuery, (data) => formatDecimal(data.performance.profitFactor), t)}
        detail={
          summaryQuery.data
            ? `${formatInteger(summaryQuery.data.performance.tradeCount)} ${t("evaluation.detail.closedTrades")}`
            : queryDetail(summaryQuery, t)
        }
      />
      <Metric
        label={t("evaluation.metric.winRate")}
        value={queryValue(summaryQuery, (data) => formatRatioAsPercent(data.performance.winRate), t)}
        detail={
          summaryQuery.data
            ? `${t("evaluation.detail.expectedR")} ${formatDecimal(summaryQuery.data.performance.expectedR)}`
            : queryDetail(summaryQuery, t)
        }
      />
      <Metric
        label={t("evaluation.metric.botDrawdown")}
        value={queryValue(benchmarkQuery, (data) => formatRatioAsPercent(calculateWorstBotDrawdown(data.points)), t)}
        detail={
          benchmarkQuery.data
            ? `${formatInteger(benchmarkQuery.data.points.length)} ${t("evaluation.detail.benchmarkPoints")}`
            : queryDetail(benchmarkQuery, t)
        }
      />
      <Metric
        label={t("evaluation.metric.llmCost")}
        value={queryValue(
          costsQuery,
          (data) => data.knownCostUsd == null ? t("evaluation.value.unavailable") : formatUsd(data.knownCostUsd),
          t,
        )}
        detail={costsQuery.data
          ? `${formatInteger(costsQuery.data.phaseCount)} ${t("evaluation.detail.phases")} · ${formatInteger(costsQuery.data.unpricedPhaseCount)} ${t("evaluation.detail.unpriced")}`
          : queryDetail(costsQuery, t)}
      />
    </div>
  );
}

function EvaluationSummaryPanel({
  summaryQuery,
}: {
  summaryQuery: UseQueryResult<EvaluationSummaryResponse, Error>;
}) {
  const { t } = useI18n();

  if (summaryQuery.isPending) {
    return <PanelLoading title={t("evaluation.panel.summary")} label={t("evaluation.loading.summary")} Icon={Activity} />;
  }

  if (summaryQuery.isError) {
    return <PanelError title={t("evaluation.error.summary")} error={summaryQuery.error} retried={() => void summaryQuery.refetch()} />;
  }

  const { performance, runRates, period, exclusions } = summaryQuery.data;

  return (
    <Panel>
      <PanelHeading Icon={Activity} title={t("evaluation.panel.summary")}>
        <FreshnessPill isStale={summaryQuery.isStale} />
        <TruncationPill truncated={summaryQuery.data.truncated} />
      </PanelHeading>
      <DataStrip
        items={[
          {
            label: t("evaluation.label.period"),
            value: periodLabel(period, t),
            detail: period.timezone,
          },
          {
            label: t("evaluation.label.totalPnl"),
            value: formatSignedJpy(performance.totalPnlJpy),
            detail: `${formatInteger(performance.tradeCount)} ${t("evaluation.detail.trades")}`,
          },
          {
            label: t("evaluation.label.profitFactor"),
            value: formatDecimal(performance.profitFactor),
            detail: `${t("evaluation.detail.win")} ${formatRatioAsPercent(performance.winRate)}`,
          },
          {
            label: t("evaluation.label.expectedR"),
            value: formatDecimal(performance.expectedR),
            detail: `${t("evaluation.detail.missingR")} ${formatInteger(performance.rUnavailableCount)}`,
          },
          {
            label: t("evaluation.label.maeMfe"),
            value: `${formatDecimal(performance.averageMaeR)} / ${formatDecimal(performance.averageMfeR)}`,
            detail: `${t("evaluation.detail.missing")} ${formatInteger(performance.maeUnavailableCount)} / ${formatInteger(performance.mfeUnavailableCount)}`,
          },
          {
            label: t("evaluation.label.decisionRuns"),
            value: formatInteger(runRates.decisionRunCount),
            detail: `${t("evaluation.detail.entry")} ${formatRatioAsPercent(runRates.entryRate)} / ${t("evaluation.label.noTrade")} ${formatRatioAsPercent(runRates.noTradeRate)}`,
          },
          {
            label: t("evaluation.label.excluded"),
            value: formatInteger((exclusions?.orderCount ?? 0) + (exclusions?.decisionRunCount ?? 0) + (exclusions?.tradeCount ?? 0)),
            detail: Object.entries(exclusions?.reasons ?? {})
              .map(([reason, count]) => `${reason} ${formatInteger(count)}`)
              .join(" / ") || t("evaluation.detail.noExclusions"),
          },
        ]}
      />
      <ActionCounts actionCounts={runRates.actionCounts} />
      <MarketRegimeTable regimes={summaryQuery.data.marketRegimes} />
    </Panel>
  );
}

function ActionCounts({ actionCounts }: { actionCounts: EvaluationSummaryResponse["runRates"]["actionCounts"] }) {
  const { t } = useI18n();

  if (actionCounts.length === 0) {
    return <EmptyState title={t("evaluation.empty.actionCounts.title")} description={t("evaluation.empty.actionCounts.description")} />;
  }

  return (
    <div className="evaluation-action-grid" aria-label={t("evaluation.label.decisionRuns")}>
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
  const { t } = useI18n();

  if (regimes.length === 0) {
    return <p className="evaluation-note">{t("evaluation.empty.marketRegimes")}</p>;
  }

  return (
    <div className="evaluation-subsection">
      <h3>{t("evaluation.table.marketRegimes")}</h3>
      <div className="evaluation-table evaluation-table--regimes" role="table" aria-label={t("evaluation.table.marketRegimePerformance")}>
        <div className="evaluation-table__row evaluation-table__row--head" role="row">
          <span role="columnheader">{t("evaluation.table.regime")}</span>
          <span role="columnheader">{t("evaluation.table.trades")}</span>
          <span role="columnheader">{t("evaluation.table.pnl")}</span>
          <span role="columnheader">{t("evaluation.table.pf")}</span>
          <span role="columnheader">{t("evaluation.table.win")}</span>
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
  const { t } = useI18n();

  if (summaryQuery.isPending) {
    return <PanelLoading title={t("evaluation.panel.killCriterion")} label={t("evaluation.loading.killCriterion")} Icon={ShieldAlert} />;
  }

  if (summaryQuery.isError) {
    return <PanelError title={t("evaluation.error.killCriterion")} error={summaryQuery.error} retried={() => void summaryQuery.refetch()} />;
  }

  const killCriterion = summaryQuery.data.killCriterion;
  const progressRatio = killCriterion.minClosedTrades === 0 ? 1 : killCriterion.closedTrades / killCriterion.minClosedTrades;

  return (
    <Panel>
      <PanelHeading Icon={ShieldAlert} title={t("evaluation.panel.killCriterion")}>
        <StatusPill label={killCriterionLabel(killCriterion, t)} tone={killCriterionTone(killCriterion)} />
        <FreshnessPill isStale={summaryQuery.isStale} />
      </PanelHeading>
      <DataStrip
        items={[
          {
            label: t("evaluation.label.closedTrades"),
            value: `${formatInteger(killCriterion.closedTrades)} / ${formatInteger(killCriterion.minClosedTrades)}`,
            detail: `${formatInteger(killCriterion.remainingTrades)} ${t("evaluation.detail.remaining")}`,
          },
          {
            label: t("evaluation.label.profitFactor"),
            value: formatDecimal(killCriterion.currentProfitFactor),
            detail: `${t("evaluation.detail.floor")} ${formatDecimal(killCriterion.minProfitFactor)}`,
          },
          {
            label: t("evaluation.label.breached"),
            value: killCriterion.breached ? t("common.yes") : t("common.no"),
          },
          {
            label: t("evaluation.label.hardHalt"),
            value: killCriterion.hardHalt ? t("common.yes") : t("common.no"),
          },
          {
            label: t("evaluation.label.totalPnl"),
            value: formatSignedJpy(summaryQuery.data.performance.totalPnlJpy),
          },
          {
            label: t("evaluation.label.noTradeRate"),
            value: formatRatioAsPercent(summaryQuery.data.runRates.noTradeRate),
          },
        ]}
      />
      <div className="evaluation-progress" aria-label={t("evaluation.panel.killCriterion")}>
        <div className="evaluation-progress__track">
          <span className="evaluation-progress__bar" style={{ width: ratioWidth(progressRatio) }} />
        </div>
        <span>
          {formatInteger(killCriterion.closedTrades)} {t("evaluation.detail.closedTrades")} / {formatInteger(killCriterion.minClosedTrades)}{" "}
          {t("evaluation.detail.requiredBeforeFloor")}
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
  const { t } = useI18n();

  if (setupsQuery.isPending) {
    return <PanelLoading title={t("evaluation.panel.setupPerformance")} label={t("evaluation.loading.setupPerformance")} Icon={AlertTriangle} isWide />;
  }

  if (setupsQuery.isError) {
    return <PanelError title={t("evaluation.error.setupPerformance")} error={setupsQuery.error} retried={() => void setupsQuery.refetch()} isWide />;
  }

  return (
    <Panel className="panel--wide">
      <PanelHeading Icon={AlertTriangle} title={t("evaluation.panel.setupPerformance")}>
        <FreshnessPill isStale={setupsQuery.isStale} />
        <TruncationPill truncated={setupsQuery.data.truncated} />
      </PanelHeading>
      <p className="evaluation-note">
        {periodLabel(setupsQuery.data.period, t)} / {setupsQuery.data.period.timezone}
      </p>
      {setupsQuery.data.setups.length === 0 ? (
        <EmptyState title={t("evaluation.empty.setupTrades.title")} description={t("evaluation.empty.setupTrades.description")} />
      ) : (
        <div className="evaluation-table evaluation-table--setups" role="table" aria-label={t("evaluation.panel.setupPerformance")}>
          <div className="evaluation-table__row evaluation-table__row--head" role="row">
            <span role="columnheader">{t("evaluation.table.setup")}</span>
            <span role="columnheader">{t("evaluation.table.trades")}</span>
            <span role="columnheader">{t("evaluation.table.pnl")}</span>
            <span role="columnheader">{t("evaluation.table.pf")}</span>
            <span role="columnheader">{t("evaluation.table.win")}</span>
            <span role="columnheader">{t("evaluation.table.expectedR")}</span>
            <span role="columnheader">{t("evaluation.table.maeMfe")}</span>
            <span role="columnheader">{t("evaluation.table.missing")}</span>
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
              <span role="cell">{missingPerformanceLabel(setup.performance, t)}</span>
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
  const { t } = useI18n();

  if (calibrationQuery.isPending) {
    return <PanelLoading title={t("evaluation.panel.calibration")} label={t("evaluation.loading.calibration")} Icon={Activity} isWide />;
  }

  if (calibrationQuery.isError) {
    return <PanelError title={t("evaluation.error.calibration")} error={calibrationQuery.error} retried={() => void calibrationQuery.refetch()} isWide />;
  }

  const hasGroups = calibrationQuery.data.bySetup.length > 0 || calibrationQuery.data.byProvider.length > 0;

  return (
    <Panel className="panel--wide">
      <PanelHeading Icon={Activity} title={t("evaluation.panel.calibration")}>
        <FreshnessPill isStale={calibrationQuery.isStale} />
        <TruncationPill truncated={calibrationQuery.data.truncated} />
      </PanelHeading>
      <p className="evaluation-note">
        {periodLabel(calibrationQuery.data.period, t)} / {calibrationQuery.data.period.timezone}
      </p>
      {hasGroups ? (
        <div className="calibration-layout">
          <CalibrationGroupList title={t("evaluation.table.bySetup")} groups={calibrationQuery.data.bySetup} />
          <CalibrationGroupList title={t("evaluation.table.byProvider")} groups={calibrationQuery.data.byProvider} />
        </div>
      ) : (
        <EmptyState title={t("evaluation.empty.calibrationBins.title")} description={t("evaluation.empty.calibrationBins.description")} />
      )}
    </Panel>
  );
}

function CalibrationGroupList({ title, groups }: { title: string; groups: CalibrationGroup[] }) {
  const { t } = useI18n();

  if (groups.length === 0) {
    return (
      <div className="calibration-groups">
        <h3>{title}</h3>
        <EmptyState title={t("evaluation.empty.groups.title")} description={t("evaluation.empty.groups.description")} />
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
            <span>{formatInteger(sumBinTrades(group.bins))} {t("evaluation.detail.trades")}</span>
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
  const { t } = useI18n();

  return (
    <div className="calibration-bin">
      <div className="calibration-bin__label">
        <span>{`${formatRatioAsPercent(bin.lowerBoundInclusive)}-${formatRatioAsPercent(bin.upperBoundInclusive)}`}</span>
        <span>{formatInteger(bin.tradeCount)} {t("evaluation.detail.trades")}</span>
      </div>
      <div className="calibration-bin__bars" aria-hidden="true">
        <span className="calibration-bin__bar calibration-bin__bar--estimated" style={{ width: probabilityWidth(bin.averageEstimatedProbability) }} />
        <span className="calibration-bin__bar calibration-bin__bar--realized" style={{ width: probabilityWidth(bin.realizedWinRate) }} />
      </div>
      <div className="calibration-bin__values">
        <span>{t("evaluation.detail.est")} {formatRatioAsPercent(bin.averageEstimatedProbability)}</span>
        <span>{t("evaluation.detail.real")} {formatRatioAsPercent(bin.realizedWinRate)}</span>
      </div>
    </div>
  );
}

function BenchmarkPanel({
  benchmarkQuery,
}: {
  benchmarkQuery: UseQueryResult<EvaluationBenchmarkResponse, Error>;
}) {
  const { t } = useI18n();

  if (benchmarkQuery.isPending) {
    return <PanelLoading title={t("evaluation.panel.benchmark")} label={t("evaluation.loading.benchmark")} Icon={Activity} isWide />;
  }

  if (benchmarkQuery.isError) {
    return <PanelError title={t("evaluation.error.benchmark")} error={benchmarkQuery.error} retried={() => void benchmarkQuery.refetch()} isWide />;
  }

  const benchmarkRows = benchmarkReturnRows(benchmarkQuery.data, t);
  const latestPoints = latestBenchmarkPoints(benchmarkQuery.data.points);

  return (
    <Panel className="panel--wide">
      <PanelHeading Icon={Activity} title={t("evaluation.panel.benchmark")}>
        <FreshnessPill isStale={benchmarkQuery.isStale} />
        {benchmarkQuery.data.points.length > latestPoints.length ? (
          <StatusPill label={`${t("evaluation.detail.latest")} ${latestPoints.length} ${t("evaluation.detail.of")} ${benchmarkQuery.data.points.length}`} tone="warning" />
        ) : null}
      </PanelHeading>
      <DataStrip
        items={[
          {
            label: t("evaluation.label.period"),
            value: periodLabel(benchmarkQuery.data.period, t),
            detail: benchmarkQuery.data.period.timezone,
          },
          {
            label: t("evaluation.label.baseline"),
            value: formatJpy(benchmarkQuery.data.baselineEquityJpy),
          },
          {
            label: t("evaluation.label.botReturn"),
            value: formatRatioAsPercent(benchmarkQuery.data.returns.botReturn),
          },
          {
            label: t("evaluation.label.buyAndHold"),
            value: formatRatioAsPercent(benchmarkQuery.data.returns.buyAndHoldReturn),
          },
          {
            label: t("evaluation.label.noTrade"),
            value: formatRatioAsPercent(benchmarkQuery.data.returns.noTradeReturn),
          },
          {
            label: t("evaluation.label.botDrawdown"),
            value: formatRatioAsPercent(calculateWorstBotDrawdown(benchmarkQuery.data.points)),
          },
        ]}
      />
      <p className="evaluation-note">{benchmarkQuery.data.assumptionsJa}</p>
      <div className="benchmark-return-list" aria-label={t("evaluation.table.benchmarkReturns")}>
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
        <EmptyState title={t("evaluation.empty.benchmarkSeries.title")} description={t("evaluation.empty.benchmarkSeries.description")} />
      ) : (
        <div className="evaluation-table evaluation-table--benchmark" role="table" aria-label={t("evaluation.table.latestBenchmarkPoints")}>
          <div className="evaluation-table__row evaluation-table__row--head" role="row">
            <span role="columnheader">{t("evaluation.table.date")}</span>
            <span role="columnheader">{t("evaluation.table.bot")}</span>
            <span role="columnheader">{t("evaluation.label.buyAndHold")}</span>
            <span role="columnheader">{t("evaluation.label.noTrade")}</span>
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
  const { t } = useI18n();

  if (costsQuery.isPending) {
    return <PanelLoading title={t("evaluation.panel.llmCostUsage")} label={t("evaluation.loading.costs")} Icon={WalletCards} isWide />;
  }

  if (costsQuery.isError) {
    return <PanelError title={t("evaluation.error.costs")} error={costsQuery.error} retried={() => void costsQuery.refetch()} isWide />;
  }

  return (
    <Panel className="panel--wide">
      <PanelHeading Icon={WalletCards} title={t("evaluation.panel.llmCostUsage")}>
        <FreshnessPill isStale={costsQuery.isStale} />
        <TruncationPill truncated={costsQuery.data.truncated} />
      </PanelHeading>
      <DataStrip
        items={[
          {
            label: t("evaluation.label.period"),
            value: periodLabel(costsQuery.data.period, t),
            detail: costsQuery.data.period.timezone,
          },
          {
            label: t("evaluation.label.totalCost"),
            value: costsQuery.data.knownCostUsd == null
              ? t("evaluation.value.unavailable")
              : formatUsd(costsQuery.data.knownCostUsd),
            detail: costsQuery.data.unpricedPhaseCount > 0
              ? t("evaluation.detail.partialKnownCost")
              : undefined,
          },
          {
            label: t("evaluation.label.phases"),
            value: formatInteger(costsQuery.data.phaseCount),
            detail: [
              `${formatInteger(costsQuery.data.unpricedPhaseCount)} ${t("evaluation.detail.unpriced")} `
                + `(${t("evaluation.detail.includingMissingUsage")} ${formatInteger(costsQuery.data.missingUsagePhaseCount)})`,
              `${formatInteger(costsQuery.data.unattributedTokenPhaseCount)} ${t("evaluation.detail.unattributedTokens")}`,
            ].join(" · "),
          },
        ]}
      />
      <ProviderCostTable costs={costsQuery.data.byProvider} />
      <ModelTokenTable models={costsQuery.data.byModel} />
    </Panel>
  );
}

function ProviderCostTable({ costs }: { costs: EvaluationCostsResponse["byProvider"] }) {
  const { t } = useI18n();

  if (costs.length === 0) {
    return <EmptyState title={t("evaluation.empty.providerUsage.title")} description={t("evaluation.empty.providerUsage.description")} />;
  }

  return (
    <div className="evaluation-subsection">
      <h3>{t("evaluation.table.providerCost")}</h3>
      <div className="evaluation-table evaluation-table--costs" role="table" aria-label={t("evaluation.table.providerCosts")}>
        <div className="evaluation-table__row evaluation-table__row--head" role="row">
          <span role="columnheader">{t("evaluation.table.provider")}</span>
          <span role="columnheader">{t("evaluation.table.cost")}</span>
          <span role="columnheader">{t("evaluation.label.phases")}</span>
          <span role="columnheader">{t("evaluation.detail.unpriced")}</span>
          <span role="columnheader">{t("evaluation.detail.includingMissingUsage")}</span>
          <span role="columnheader">{t("evaluation.detail.unattributedTokens")}</span>
        </div>
        {costs.map((cost) => (
          <div className="evaluation-table__row" role="row" key={cost.provider}>
            <span role="cell">{cost.provider}</span>
            <span role="cell">
              {cost.knownCostUsd == null
                ? t("evaluation.value.unavailable")
                : formatUsd(cost.knownCostUsd)}
              {cost.knownCostUsd != null && cost.unpricedPhaseCount > 0
                ? ` (${t("evaluation.value.partial")})`
                : ""}
            </span>
            <span role="cell">{formatInteger(cost.phaseCount)}</span>
            <span role="cell">{formatInteger(cost.unpricedPhaseCount)}</span>
            <span role="cell">{formatInteger(cost.missingUsagePhaseCount)}</span>
            <span role="cell">{formatInteger(cost.unattributedTokenPhaseCount)}</span>
          </div>
        ))}
      </div>
    </div>
  );
}

function ModelTokenTable({ models }: { models: EvaluationCostsResponse["byModel"] }) {
  const { t } = useI18n();

  if (models.length === 0) {
    return <EmptyState title={t("evaluation.empty.modelTokens.title")} description={t("evaluation.empty.modelTokens.description")} />;
  }

  return (
    <div className="evaluation-subsection">
      <h3>{t("evaluation.table.modelTokens")}</h3>
      <div className="evaluation-table evaluation-table--models" role="table" aria-label={t("evaluation.table.modelTokenUsage")}>
        <div className="evaluation-table__row evaluation-table__row--head" role="row">
          <span role="columnheader">{t("evaluation.table.model")}</span>
          <span role="columnheader">{t("evaluation.table.input")}</span>
          <span role="columnheader">{t("evaluation.table.output")}</span>
          <span role="columnheader">{t("evaluation.table.reasoning")}</span>
          <span role="columnheader">{t("evaluation.table.cacheCreate")}</span>
          <span role="columnheader">{t("evaluation.table.cacheRead")}</span>
        </div>
        {models.map((model) => (
          <div className="evaluation-table__row" role="row" key={model.model}>
            <span role="cell">{model.model}</span>
            <span role="cell">{formatInteger(model.inputTokens)}</span>
            <span role="cell">{formatInteger(model.outputTokens)}</span>
            <span role="cell">{formatInteger(model.reasoningOutputTokens)}</span>
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
  const { t } = useI18n();

  return (
    <Panel className={isWide ? "panel--wide" : undefined}>
      <PanelHeading Icon={Icon} title={title}>
        <StatusPill label={t("common.loading")} tone="loading" />
      </PanelHeading>
      <div className="loading-row" role="status">
        <span className="loading-dot" aria-hidden="true" />
        <span>{label}</span>
      </div>
    </Panel>
  );
}

function PanelError({ title, error, retried, isWide = false }: { title: string; error: unknown; retried: () => void; isWide?: boolean }) {
  const { t } = useI18n();

  return (
    <Panel className={isWide ? "panel--wide" : undefined}>
      <EmptyState
        title={title}
        description={describeError(error)}
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

function FreshnessPill({ isStale }: { isStale: boolean }) {
  const { t } = useI18n();

  return <StatusPill label={isStale ? t("common.stale") : t("common.fresh")} tone={isStale ? "warning" : "positive"} />;
}

function TruncationPill({ truncated }: { truncated: boolean }) {
  const { t } = useI18n();

  return truncated ? <StatusPill label={t("evaluation.status.truncated")} tone="warning" /> : <StatusPill label={t("evaluation.status.complete")} tone="neutral" />;
}

function queryValue<TData>(query: UseQueryResult<TData, Error>, selected: (data: TData) => string, t: Translate): string {
  if (query.isPending) {
    return t("common.loading");
  }

  if (query.isError) {
    return t("common.error");
  }

  return selected(query.data);
}

function queryDetail<TData>(query: UseQueryResult<TData, Error>, t: Translate): string {
  if (query.isError) {
    return describeError(query.error);
  }

  return query.isPending ? t("common.waitingForApi") : t("common.notReported");
}

function periodLabel(period: EvaluationSummaryResponse["period"], t: Translate): string {
  return `${period.from} ${t("overview.detail.periodTo")} ${period.to}`;
}

function missingPerformanceLabel(performance: Performance, t: Translate): string {
  return `${t("evaluation.detail.missing")} R ${formatInteger(performance.rUnavailableCount)} / MAE ${formatInteger(performance.maeUnavailableCount)} / MFE ${formatInteger(performance.mfeUnavailableCount)}`;
}

function killCriterionLabel(killCriterion: EvaluationSummaryResponse["killCriterion"], t: Translate): string {
  if (killCriterion.hardHalt) {
    return t("evaluation.status.hardHalt");
  }

  return killCriterion.breached ? t("evaluation.status.breached") : t("evaluation.status.withinBounds");
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

function benchmarkReturnRows(data: EvaluationBenchmarkResponse, t: Translate) {
  const rows = [
    {
      label: t("evaluation.benchmark.botRealized"),
      value: data.returns.botReturn,
    },
    {
      label: t("evaluation.label.buyAndHold"),
      value: data.returns.buyAndHoldReturn,
    },
    {
      label: t("evaluation.label.noTrade"),
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
