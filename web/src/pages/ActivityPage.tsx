import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useInfiniteQuery, useQuery } from "@tanstack/react-query";
import ChevronRight from "lucide-react/dist/esm/icons/chevron-right.mjs";
import CircleAlert from "lucide-react/dist/esm/icons/circle-alert.mjs";
import CircleCheck from "lucide-react/dist/esm/icons/circle-check.mjs";
import Clock3 from "lucide-react/dist/esm/icons/clock-3.mjs";
import Ban from "lucide-react/dist/esm/icons/ban.mjs";
import RefreshCw from "lucide-react/dist/esm/icons/refresh-cw.mjs";
import X from "lucide-react/dist/esm/icons/x.mjs";
import {
  DECISION_RUN_FILTER_STORAGE_KEY,
  DECISION_RUN_FILTERS,
  decisionRunRefetchInterval,
  fetchDecisionRuns,
  opsDecisionRunDetailQuery,
  type DecisionRunFilterOption,
  type OpsDecisionRunDetailResponse,
  type OpsDecisionRunSummaryResponse,
} from "../api/ops";
import { useI18n } from "../i18n/useI18n";
import { EmptyState } from "../ui/components/EmptyState";
import { SectionHeader } from "../ui/components/SectionHeader";
import { describeError, formatDateTime } from "../ui/format";

export function ActivityPage() {
  const [filter, setFilter] = useState<DecisionRunFilterOption>(loadRunFilter);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const selectedRunButtonRef = useRef<HTMLButtonElement | null>(null);
  const runsQuery = useInfiniteQuery({
    queryKey: ["ops", "decision-runs", filter],
    queryFn: ({ pageParam }) => fetchDecisionRuns({
      before: pageParam,
      filter: filter === "ALL" ? null : filter,
    }),
    initialPageParam: null as string | null,
    getNextPageParam: (lastPage) => lastPage.nextBefore ?? undefined,
    staleTime: 15_000,
    refetchInterval: (query) => decisionRunRefetchInterval(query.state.data?.pages.length ?? 0),
  });
  const { locale, t } = useI18n();
  const runs = useMemo(
    () => deduplicateRuns(runsQuery.data?.pages.flatMap((page) => page.runs) ?? []),
    [runsQuery.data?.pages],
  );
  const latestMarketQuote = runsQuery.data?.pages[0]?.latestMarketQuote ?? null;
  const activeSelectedId = selectedId && runs.some((run) => run.invocationId === selectedId) ? selectedId : null;
  const detailQuery = useQuery(opsDecisionRunDetailQuery(activeSelectedId));
  const closeDetail = useCallback(() => {
    setSelectedId(null);
    window.setTimeout(() => selectedRunButtonRef.current?.focus(), 0);
  }, []);

  useEffect(() => {
    window.localStorage.setItem(DECISION_RUN_FILTER_STORAGE_KEY, filter);
  }, [filter]);

  return (
    <div className="decision-runs-page page-stack">
      <SectionHeader
        eyebrow="Operations"
        title="Activity"
        description={t("activity.runs.description")}
        action={
          <button
            className="icon-text-button icon-text-button--prominent"
            type="button"
            onClick={() => void runsQuery.refetch()}
            disabled={runsQuery.isFetching}
          >
            <RefreshCw size={16} aria-hidden="true" />
            {runsQuery.isFetching ? t("common.refreshing") : t("common.refresh")}
          </button>
        }
      />

      <div className="run-filterbar" role="group" aria-label={t("activity.runs.filters.aria")}>
        {DECISION_RUN_FILTERS.map((outcome) => (
          <button
            className="run-filter"
            type="button"
            aria-pressed={filter === outcome}
            key={outcome}
            onClick={() => {
              setFilter(outcome);
              setSelectedId(null);
            }}
          >
            {filterLabel(outcome, t)}
            {filter === outcome ? (
              <span className="run-filter__count">{runs.length} {t("activity.runs.filter.loaded")}</span>
            ) : null}
          </button>
        ))}
      </div>

      {runsQuery.isPending ? <RunListNotice text={t("activity.runs.loading")} /> : null}
      {runsQuery.isError ? (
        <RunListNotice
          text={`${t("activity.runs.error")}: ${describeError(runsQuery.error, locale)}`}
          action={() => void runsQuery.refetch()}
        />
      ) : null}
      {runsQuery.isSuccess ? (
        <div className={activeSelectedId ? "decision-runs-layout decision-runs-layout--detail" : "decision-runs-layout"}>
          <main className="decision-run-list" aria-label={t("activity.runs.list.aria")}>
            {runs.length === 0 ? (
              <EmptyState
                title={t(runsQuery.hasNextPage ? "activity.runs.empty.scanTitle" : "activity.runs.empty.title")}
                description={t(
                  runsQuery.hasNextPage ? "activity.runs.empty.scanDescription" : "activity.runs.empty.description",
                )}
              />
            ) : (
              runs.map((run) => (
                <RunRow
                  run={run}
                  latestMarketQuote={latestMarketQuote}
                  selected={run.invocationId === activeSelectedId}
                  selectedChanged={(button) => {
                    selectedRunButtonRef.current = button;
                    setSelectedId(run.invocationId);
                  }}
                  key={run.invocationId}
                />
              ))
            )}
            {runsQuery.hasNextPage ? (
              <button
                className="run-load-more"
                type="button"
                disabled={runsQuery.isFetchingNextPage}
                onClick={() => void runsQuery.fetchNextPage()}
              >
                {runsQuery.isFetchingNextPage ? t("activity.runs.loadingOlder") : t("activity.runs.loadOlder")}
              </button>
            ) : null}
            {runsQuery.isFetchNextPageError ? <p className="run-detail-notice--error">{t("activity.runs.errorOlder")}</p> : null}
          </main>
          {activeSelectedId ? (
            <RunDetailPane
              detail={detailQuery.data ?? null}
              isPending={detailQuery.isPending}
              error={detailQuery.error}
              closed={closeDetail}
            />
          ) : null}
        </div>
      ) : null}
    </div>
  );
}

function RunRow({
  run,
  latestMarketQuote,
  selected,
  selectedChanged,
}: {
  run: OpsDecisionRunSummaryResponse;
  latestMarketQuote: OpsDecisionRunDetailResponse["latestMarketQuote"];
  selected: boolean;
  selectedChanged: (button: HTMLButtonElement) => void;
}) {
  const { locale, t } = useI18n();
  const primaryReason = runSummaryPrimaryReason(run);
  const order = run.order;

  return (
    <button
      className="decision-run-row"
      type="button"
      aria-selected={selected}
      data-outcome={run.outcome}
      onClick={(event) => selectedChanged(event.currentTarget)}
    >
      <span className="decision-run-row__rail"><span className="decision-run-row__dot" /></span>
      <span className="decision-run-card">
        <span className="decision-run-card__top">
          <time>{formatDateTime(run.startedAt, locale)}</time>
          <span className="decision-run-card__duration">{formatDuration(run.durationMillis)}</span>
          <span className={`run-outcome run-outcome--${run.outcome.toLowerCase().replaceAll("_", "-")}`}>
            <OutcomeIcon outcome={run.outcome} />
            {runOutcomeLabel(run.outcome, t)}
          </span>
          {run.hasProcessFailure ? (
            <span className="run-process-failure" title={t("activity.runs.processFailure")}>
              <CircleAlert size={15} aria-label={t("activity.runs.processFailure")} />
            </span>
          ) : null}
          <ChevronRight className="decision-run-card__arrow" size={17} aria-hidden="true" />
        </span>
        <span className="decision-run-card__headline">
          <strong>{order ? `${order.side} ${order.orderType} · ${order.sizeBtc} BTC` : run.action ?? run.status}</strong>
          <code>{run.mode}</code>
        </span>
        <span className="decision-run-card__reason">
          <span>{primaryReason ?? t("activity.runs.reason.none")}</span>
          {run.finalReason && run.finalReason !== primaryReason ? (
            <small><strong>{t("activity.runs.label.finalReason")}</strong> {run.finalReason}</small>
          ) : null}
        </span>
        <span className="decision-run-card__facts">
          <span>{t("activity.runs.label.price")} <strong>{order?.limitPriceJpy ?? "—"}</strong></span>
          <span>{t("activity.runs.label.currentQuote")} <strong>{formatReferenceQuote(run, latestMarketQuote, locale)}</strong></span>
          <span>{t("activity.runs.label.distance")} <strong>{formatPriceDistance(run, latestMarketQuote)}</strong></span>
          <span>{t("activity.runs.label.effectiveExpiry")} <strong>{formatExpiry(order?.expiresAt, locale)}</strong></span>
        </span>
      </span>
    </button>
  );
}

function RunDetailPane({
  detail,
  isPending,
  error,
  closed,
}: {
  detail: OpsDecisionRunDetailResponse | null;
  isPending: boolean;
  error: unknown;
  closed: () => void;
}) {
  const closeButtonRef = useRef<HTMLButtonElement>(null);
  const [rawOpen, setRawOpen] = useState(false);
  const { locale, t } = useI18n();

  useEffect(() => {
    closeButtonRef.current?.focus();
    const keyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") closed();
    };
    window.addEventListener("keydown", keyDown);
    return () => window.removeEventListener("keydown", keyDown);
  }, [closed]);

  return (
    <aside className="decision-run-detail" aria-label={t("activity.runs.detail.aria")}>
      <header className="decision-run-detail__header">
        <span className="decision-run-detail__eyebrow">Decision run</span>
        {detail ? (
          <span className={`run-outcome run-outcome--${detail.summary.outcome.toLowerCase().replaceAll("_", "-")}`}>
            <OutcomeIcon outcome={detail.summary.outcome} />
            {runOutcomeLabel(detail.summary.outcome, t)}
          </span>
        ) : null}
        <button
          ref={closeButtonRef}
          className="icon-only-button decision-run-detail__close"
          type="button"
          aria-label={t("activity.runs.detail.close")}
          onClick={closed}
        >
          <X size={18} aria-hidden="true" />
        </button>
        <h2>{detail?.decision?.action ?? detail?.summary.status ?? t("activity.runs.detail.loading")}</h2>
        <code>{detail?.summary.invocationId ?? "—"}</code>
      </header>
      <div className="decision-run-detail__scroll">
        {isPending ? <p className="run-detail-notice">{t("activity.runs.detail.loading")}</p> : null}
        {error ? <p className="run-detail-notice run-detail-notice--error">{describeError(error, locale)}</p> : null}
        {detail ? <RunDetailContent detail={detail} rawOpen={rawOpen} rawToggled={() => setRawOpen((open) => !open)} /> : null}
      </div>
    </aside>
  );
}

function RunDetailContent({
  detail,
  rawOpen,
  rawToggled,
}: {
  detail: OpsDecisionRunDetailResponse;
  rawOpen: boolean;
  rawToggled: () => void;
}) {
  const { locale, t } = useI18n();
  const decision = detail.decision;
  const intent = detail.intent;
  const safety = detail.safetyViolation;
  const order = detail.summary.order ?? detail.orders.find((candidate) => candidate.side === "BUY") ?? null;

  return (
    <>
      {order ? <p className="run-order-explanation">{orderExplanation(order, locale)}</p> : null}

      <DetailSection index="01" title={t("activity.runs.section.orderConditions")}>
        <FactGrid facts={[
          [t("activity.runs.label.mode"), detail.summary.mode],
          [t("activity.runs.label.order"), order ? `${order.side} ${order.orderType}` : null],
          [t("activity.runs.label.size"), order ? `${order.sizeBtc} BTC` : intent ? `${intent.sizeBtc} BTC` : null],
          [t("activity.runs.label.price"), order?.limitPriceJpy ?? intent?.priceJpy],
          [t("activity.runs.label.currentQuote"), formatReferenceQuote(detail.summary, detail.latestMarketQuote, locale)],
          [t("activity.runs.label.distance"), formatPriceDistance(detail.summary, detail.latestMarketQuote)],
          [t("activity.runs.label.quoteNotice"), detail.latestMarketQuote?.stale ? t("activity.runs.quote.stale") : t("activity.runs.quote.reference"), true],
        ]} />
        <RunOrderRecords orders={detail.orders} />
        <RunExecutionRecords executions={detail.executions} />
      </DetailSection>

      <DetailSection index="02" title={t("activity.runs.section.expiry")}>
        <FactGrid facts={[
          [t("activity.runs.label.effectiveExpiry"), order?.expiresAt ? formatDateTime(order.expiresAt, locale) : t("activity.runs.expiry.unrecorded")],
          [t("activity.runs.label.remaining"), formatExpiry(order?.expiresAt, locale)],
          [t("activity.runs.label.expirySource"), order?.expirySource],
          [t("activity.runs.label.effectiveTtl"), order?.effectiveTtlSeconds == null ? null : `${order.effectiveTtlSeconds}s`],
          [t("activity.runs.label.timeStop"), intent?.timeStopAt],
          [t("activity.runs.label.expiredAt"), order?.expiredAt],
          [t("activity.runs.label.canceledAt"), order?.canceledAt],
          [t("activity.runs.label.cancelReason"), order?.cancelReason, true],
          [t("activity.runs.label.canceledByRun"), order?.canceledByDecisionRunId, true],
          [t("activity.runs.label.strategyEvaluation"), formatStrategyEvaluation(order, t), true],
        ]} />
      </DetailSection>

      <DetailSection index="03" title={t("activity.runs.section.safety")}>
        <div className="run-value-comparison">
          <div><small>{t("activity.runs.comparison.llm")}</small><strong>{decision?.expectedRMultiple ?? "—"}</strong><span>expectedR</span></div>
          <span aria-hidden="true">→</span>
          <div><small>{t("activity.runs.comparison.code")}</small><strong>{safety?.measuredValue ?? "—"}</strong><span>{safety ? `limit ${safety.limitValue}` : "—"}</span></div>
        </div>
        <FactGrid facts={[
          [t("activity.runs.label.rule"), safety?.rule],
          [t("activity.runs.label.verdict"), detail.falsification?.verdict],
          [t("activity.runs.label.falsifierReason"), detail.falsification?.reasonJa, true],
          [t("activity.runs.label.stop"), intent?.protectiveStopPriceJpy],
          [t("activity.runs.label.takeProfit"), intent?.takeProfitPriceJpy],
          [t("activity.runs.label.finalReason"), detail.summary.finalReason],
          [t("activity.runs.label.message"), safety?.messageJa, true],
        ]} />
      </DetailSection>

      <DetailSection index="04" title={t("activity.runs.section.processingPath")}>
        <ol className="run-phase-list">
          {detail.phases.map((phase) => (
            <li className="run-phase" data-state={phase.status.toLowerCase()} key={phase.key}>
              <span className="run-phase__rail"><span /></span>
              <span><strong>{phaseLabel(phase.key, t)}</strong><code>{phase.status}</code><small>{phase.detail ?? "—"}</small></span>
            </li>
          ))}
        </ol>
        {detail.summary.hasProcessFailure && detail.summary.errorMessage ? (
          <p className="run-detail-notice run-detail-notice--warning">
            <CircleAlert size={16} aria-hidden="true" /> {detail.summary.errorMessage}
          </p>
        ) : null}
        <FactGrid facts={[
          [t("activity.runs.label.action"), decision?.action],
          [t("activity.runs.label.provider"), decision?.provider],
          [t("activity.runs.label.parentPlan"), intent?.parentTradePlanId],
          [t("activity.runs.label.setupTags"), formatJsonList(intent?.setupTagsJson), true],
          [t("activity.runs.label.thesis"), intent?.thesisJa, true],
          [t("activity.runs.label.invalidation"), formatJsonList(intent?.invalidationConditionsJaJson), true],
          [t("activity.runs.label.runtimeError"), detail.summary.errorMessage, true],
          [t("activity.runs.label.reason"), decision?.reasonJa, true],
        ]} />
        <button
          className="run-raw-toggle"
          type="button"
          aria-expanded={rawOpen}
          aria-label={rawOpen ? t("activity.runs.raw.hide") : t("activity.runs.raw.show")}
          onClick={rawToggled}
        >
          {rawOpen ? t("activity.runs.raw.hide") : t("activity.runs.raw.show")}
          <span>{detail.raw.length}</span>
        </button>
        {rawOpen ? <pre className="run-raw-block">{JSON.stringify(detail.raw, null, 2)}</pre> : null}
      </DetailSection>
    </>
  );
}

function DetailSection({ index, title, children }: { index: string; title: string; children: React.ReactNode }) {
  return (
    <section className="run-detail-section">
      <div className="run-detail-section__heading"><span>{index}</span><h3>{title}</h3></div>
      {children}
    </section>
  );
}

function FactGrid({ facts }: { facts: Array<[string, string | null | undefined, boolean?]> }) {
  return (
    <dl className="run-fact-grid">
      {facts.map(([label, value, wide]) => (
        <div className={wide ? "run-fact run-fact--wide" : "run-fact"} key={label}>
          <dt>{label}</dt><dd>{value || "—"}</dd>
        </div>
      ))}
    </dl>
  );
}

function RunOrderRecords({ orders }: { orders: OpsDecisionRunDetailResponse["orders"] }) {
  const { t } = useI18n();

  return (
    <div className="run-records">
      <h4>{t("activity.runs.records.orders")}</h4>
      {orders.length === 0 ? <p>{t("activity.runs.records.noOrders")}</p> : orders.map((order, index) => (
        <article className="run-record" key={order.orderId}>
          <h5>{t("activity.runs.records.orderNumber")} {index + 1}</h5>
          <FactGrid facts={[
            ["order ID", order.orderId],
            [t("activity.runs.label.intentId"), order.intentId],
            [t("activity.runs.label.position"), order.positionId],
            [t("activity.runs.label.tradeGroup"), order.tradeGroupId],
            [t("activity.runs.label.order"), `${order.side} ${order.orderType}`],
            [t("activity.runs.label.status"), order.status],
            [t("activity.runs.label.size"), `${order.sizeBtc} BTC`],
            [t("activity.runs.label.price"), order.limitPriceJpy],
            [t("activity.runs.label.effectiveExpiry"), order.expiresAt],
            [t("activity.runs.label.expirySource"), order.expirySource],
            [t("activity.runs.label.expiredAt"), order.expiredAt],
            [t("activity.runs.label.canceledAt"), order.canceledAt],
            [t("activity.runs.label.cancelReason"), order.cancelReason, true],
            [t("activity.runs.label.canceledByRun"), order.canceledByDecisionRunId, true],
            [t("activity.runs.label.strategyEvaluation"), formatStrategyEvaluation(order, t), true],
            [t("activity.runs.label.createdAt"), order.createdAt],
            [t("activity.runs.label.entryReason"), order.reasonJa, true],
          ]} />
        </article>
      ))}
    </div>
  );
}

function RunExecutionRecords({ executions }: { executions: OpsDecisionRunDetailResponse["executions"] }) {
  const { t } = useI18n();

  return (
    <div className="run-records">
      <h4>{t("activity.runs.records.executions")}</h4>
      {executions.length === 0 ? <p>{t("activity.runs.records.noExecutions")}</p> : executions.map((execution, index) => (
        <article className="run-record" key={execution.executionId}>
          <h5>{t("activity.runs.records.executionNumber")} {index + 1}</h5>
          <FactGrid facts={[
            ["execution ID", execution.executionId],
            ["order ID", execution.orderId],
            [t("activity.runs.label.position"), execution.positionId],
            [t("activity.runs.label.side"), execution.side],
            [t("activity.runs.label.price"), execution.priceJpy],
            [t("activity.runs.label.size"), `${execution.sizeBtc} BTC`],
            [t("activity.runs.label.realizedPnl"), execution.realizedPnlJpy],
            [t("activity.runs.label.executedAt"), execution.executedAt],
          ]} />
        </article>
      ))}
    </div>
  );
}

function RunListNotice({ text, action }: { text: string; action?: () => void }) {
  const { t } = useI18n();
  return (
    <div className="run-list-notice"><p>{text}</p>{action ? <button type="button" onClick={action}>{t("common.retry")}</button> : null}</div>
  );
}

function loadRunFilter(): DecisionRunFilterOption {
  const saved = window.localStorage.getItem(DECISION_RUN_FILTER_STORAGE_KEY);
  return DECISION_RUN_FILTERS.find((filter) => filter === saved) ?? "ALL";
}

function deduplicateRuns(runs: OpsDecisionRunSummaryResponse[]): OpsDecisionRunSummaryResponse[] {
  const uniqueRuns = new Map<string, OpsDecisionRunSummaryResponse>();
  runs.forEach((run) => uniqueRuns.set(run.invocationId, run));

  return [...uniqueRuns.values()].sort((first, second) => {
    const startedAtComparison = Date.parse(second.startedAt) - Date.parse(first.startedAt);
    return startedAtComparison || second.invocationId.localeCompare(first.invocationId);
  });
}

type AppLocale = ReturnType<typeof useI18n>["locale"];

function formatReferenceQuote(
  run: OpsDecisionRunSummaryResponse,
  quote: OpsDecisionRunDetailResponse["latestMarketQuote"],
  locale: AppLocale,
): string {
  if (!quote) return "—";

  const stale = quote.stale ? (locale === "ja" ? " · stale" : " · stale") : "";
  const price = referenceQuotePrice(run, quote);
  const quoteName = run.order?.side === "SELL"
    ? (locale === "ja" ? "買" : "bid")
    : (locale === "ja" ? "売" : "ask");
  return `${quoteName} ${price} @ ${formatDateTime(quote.observedAt, locale)}${stale}`;
}

function formatPriceDistance(
  run: OpsDecisionRunSummaryResponse,
  quote: OpsDecisionRunDetailResponse["latestMarketQuote"],
): string {
  const limit = Number(run.order?.limitPriceJpy);
  const quotePrice = Number(referenceQuotePrice(run, quote));
  if (!Number.isFinite(limit) || !Number.isFinite(quotePrice) || quotePrice === 0) return "—";

  const distance = run.order?.side === "SELL" ? limit - quotePrice : quotePrice - limit;
  const ratio = distance / quotePrice * 100;
  return `${distance.toLocaleString()} JPY (${ratio.toFixed(2)}%)`;
}

function referenceQuotePrice(
  run: OpsDecisionRunSummaryResponse,
  quote: OpsDecisionRunDetailResponse["latestMarketQuote"],
): string | undefined {
  if (run.order?.side === "SELL") return quote?.bidPriceJpy;

  return quote?.askPriceJpy;
}

function formatStrategyEvaluation(
  order: OpsDecisionRunDetailResponse["orders"][number] | null,
  t: Translator,
): string | null {
  if (!order) return null;
  if (order.strategyEvaluationEligible) return t("activity.runs.evaluation.eligible");
  if (order.strategyEvaluationExclusionReason === "LIFECYCLE_MONITORING_DELAY") {
    return `${t("activity.runs.evaluation.monitoringDelay")} (${order.lifecycleDelaySeconds ?? "—"}s)`;
  }

  return t("activity.runs.evaluation.missingEvidence");
}

function formatExpiry(expiresAt: string | null | undefined, locale: AppLocale): string {
  if (!expiresAt) return locale === "ja" ? "期限記録なし" : "Expiry not recorded";

  const remainingMillis = Date.parse(expiresAt) - Date.now();
  const absoluteSeconds = Math.floor(Math.abs(remainingMillis) / 1_000);
  const hours = Math.floor(absoluteSeconds / 3_600);
  const minutes = Math.floor((absoluteSeconds % 3_600) / 60);
  const duration = hours > 0 ? `${hours}h ${minutes}m` : `${minutes}m`;
  const relation = remainingMillis > 0
    ? (locale === "ja" ? `残り ${duration}` : `${duration} remaining`)
    : (locale === "ja" ? `${duration} 超過` : `${duration} overdue`);

  return `${formatDateTime(expiresAt, locale)} · ${relation}`;
}

function orderExplanation(
  order: NonNullable<OpsDecisionRunSummaryResponse["order"]>,
  locale: AppLocale,
): string {
  if (order.orderType === "LIMIT" && order.limitPriceJpy) {
    const quoteName = order.side === "BUY"
      ? (locale === "ja" ? "最良売気配" : "best ask")
      : (locale === "ja" ? "最良買気配" : "best bid");
    const comparison = order.side === "BUY" ? (locale === "ja" ? "以下" : "or lower") : (locale === "ja" ? "以上" : "or higher");

    return locale === "ja"
      ? `${quoteName}が ${order.limitPriceJpy} JPY ${comparison}になると、${order.sizeBtc} BTC の paper 約定を作成します。`
      : `A paper fill for ${order.sizeBtc} BTC is created when the ${quoteName} reaches ${order.limitPriceJpy} JPY ${comparison}.`;
  }

  return locale === "ja"
    ? `${order.orderType} 条件で ${order.sizeBtc} BTC の paper 約定を待っています。`
    : `Waiting for a paper fill of ${order.sizeBtc} BTC under the ${order.orderType} condition.`;
}

function runSummaryPrimaryReason(run: OpsDecisionRunSummaryResponse): string | null {
  if (run.outcome === "FAILED" || run.outcome === "ACTION_REQUIRED") {
    return run.errorMessage ?? run.finalReason ?? run.safetyMessageJa ?? run.reasonJa ?? null;
  }
  if (run.outcome === "NO_ENTRY") {
    return run.finalReason ?? run.reasonJa ?? run.errorMessage ?? null;
  }

  return run.errorMessage ?? run.safetyMessageJa ?? run.reasonJa ?? run.finalReason ?? null;
}

function formatJsonList(value: string | null | undefined): string | null {
  if (!value) return null;

  try {
    const parsed: unknown = JSON.parse(value);
    return Array.isArray(parsed) ? parsed.map(String).join(" / ") : value;
  } catch {
    return value;
  }
}

function formatDuration(value: number | null | undefined): string {
  if (value == null) return "running";
  if (value < 1_000) return `${value}ms`;
  if (value < 60_000) return `${(value / 1_000).toFixed(1)}s`;
  return `${Math.floor(value / 60_000)}m ${Math.floor((value % 60_000) / 1_000)}s`;
}

type Translator = ReturnType<typeof useI18n>["t"];

function filterLabel(filter: DecisionRunFilterOption, t: Translator): string {
  const keys = {
    ALL: "activity.runs.filter.all",
    ACTION_REQUIRED: "activity.runs.filter.actionRequired",
    WAITING: "activity.runs.filter.waiting",
    EXPIRING: "activity.runs.filter.expiring",
    FILLED: "activity.runs.filter.filled",
    DENIED: "activity.runs.filter.denied",
    RUNNING: "activity.runs.filter.running",
    EXPIRED: "activity.runs.filter.expired",
    CANCELED: "activity.runs.filter.canceled",
    NO_ENTRY: "activity.runs.filter.noEntry",
  } as const;
  return t(keys[filter]);
}

function runOutcomeLabel(outcome: OpsDecisionRunSummaryResponse["outcome"], t: Translator): string {
  const keys = {
    ACTION_REQUIRED: "activity.runs.outcome.overdue",
    WAITING: "activity.runs.outcome.waiting",
    EXPIRING: "activity.runs.outcome.expiring",
    FILLED: "activity.runs.outcome.filled",
    EXPIRED: "activity.runs.outcome.expired",
    CANCELED: "activity.runs.outcome.canceled",
    NO_ENTRY: "activity.runs.outcome.noEntry",
    DENIED: "activity.runs.outcome.denied",
    RUNNING: "activity.runs.outcome.running",
    FAILED: "activity.runs.outcome.failed",
  } as const;
  return t(keys[outcome]);
}

function OutcomeIcon({ outcome }: { outcome: OpsDecisionRunSummaryResponse["outcome"] }) {
  const Icon = outcome === "WAITING" || outcome === "EXPIRING" || outcome === "RUNNING"
    ? Clock3
    : outcome === "FILLED"
      ? CircleCheck
      : outcome === "EXPIRED" || outcome === "CANCELED" || outcome === "NO_ENTRY" || outcome === "DENIED"
        ? Ban
        : CircleAlert;

  return <Icon size={14} aria-hidden="true" />;
}

function phaseLabel(phase: string, t: Translator): string {
  const keys = {
    TRIGGER: "activity.runs.phase.trigger",
    PROPOSER: "activity.runs.phase.proposer",
    INTENT: "activity.runs.phase.intent",
    FALSIFIER: "activity.runs.phase.falsifier",
    SAFETY: "activity.runs.phase.safety",
    ORDER_EXECUTION: "activity.runs.phase.execution",
  } as const;
  return t(keys[phase as keyof typeof keys] ?? "activity.runs.phase.unknown");
}
