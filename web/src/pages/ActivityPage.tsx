import { useEffect, useMemo, useRef, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import ChevronRight from "lucide-react/dist/esm/icons/chevron-right.mjs";
import RefreshCw from "lucide-react/dist/esm/icons/refresh-cw.mjs";
import X from "lucide-react/dist/esm/icons/x.mjs";
import {
  DECISION_RUN_FILTER_STORAGE_KEY,
  DECISION_RUN_OUTCOME_FILTERS,
  opsDecisionRunDetailQuery,
  opsDecisionRunsQuery,
  type DecisionRunOutcomeFilter,
  type OpsDecisionRunDetailResponse,
  type OpsDecisionRunSummaryResponse,
} from "../api/ops";
import { useI18n } from "../i18n/useI18n";
import { EmptyState } from "../ui/components/EmptyState";
import { SectionHeader } from "../ui/components/SectionHeader";
import { describeError, formatDateTime } from "../ui/format";

export function ActivityPage() {
  const [filter, setFilter] = useState<DecisionRunOutcomeFilter>(loadRunFilter);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const runsQuery = useQuery(opsDecisionRunsQuery);
  const { locale, t } = useI18n();
  const runs = useMemo(() => runsQuery.data?.runs ?? [], [runsQuery.data?.runs]);
  const visibleRuns = useMemo(
    () => runs.filter((run) => filter === "ALL" || run.outcome === filter),
    [filter, runs],
  );
  const activeSelectedId = selectedId && visibleRuns.some((run) => run.invocationId === selectedId) ? selectedId : null;
  const detailQuery = useQuery(opsDecisionRunDetailQuery(activeSelectedId));

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
        {DECISION_RUN_OUTCOME_FILTERS.map((outcome) => (
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
            {outcomeLabel(outcome, t)}
            <span className="run-filter__count">{outcomeCount(outcome, runs)}</span>
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
            {visibleRuns.length === 0 ? (
              <EmptyState title={t("activity.runs.empty.title")} description={t("activity.runs.empty.description")} />
            ) : (
              visibleRuns.map((run) => (
                <RunRow
                  run={run}
                  selected={run.invocationId === activeSelectedId}
                  selectedChanged={() => setSelectedId(run.invocationId)}
                  key={run.invocationId}
                />
              ))
            )}
          </main>
          {activeSelectedId ? (
            <RunDetailPane
              detail={detailQuery.data ?? null}
              isPending={detailQuery.isPending}
              error={detailQuery.error}
              closed={() => setSelectedId(null)}
            />
          ) : null}
        </div>
      ) : null}
    </div>
  );
}

function RunRow({
  run,
  selected,
  selectedChanged,
}: {
  run: OpsDecisionRunSummaryResponse;
  selected: boolean;
  selectedChanged: () => void;
}) {
  const { locale, t } = useI18n();

  return (
    <button
      className="decision-run-row"
      type="button"
      aria-selected={selected}
      data-outcome={run.outcome}
      onClick={selectedChanged}
    >
      <span className="decision-run-row__rail"><span className="decision-run-row__dot" /></span>
      <span className="decision-run-card">
        <span className="decision-run-card__top">
          <time>{formatDateTime(run.startedAt, locale)}</time>
          <span className="decision-run-card__duration">{formatDuration(run.durationMillis)}</span>
          <span className={`run-outcome run-outcome--${run.outcome.toLowerCase().replace("_", "-")}`}>
            {outcomeLabel(run.outcome as DecisionRunOutcomeFilter, t)}
          </span>
          <ChevronRight className="decision-run-card__arrow" size={17} aria-hidden="true" />
        </span>
        <span className="decision-run-card__headline">
          <strong>{run.action ?? run.status}</strong>
          <code>{run.triggerKind ?? "MANUAL"}</code>
        </span>
        <span className="decision-run-card__reason">
          {run.safetyMessageJa ?? run.errorMessage ?? run.reasonJa ?? t("activity.runs.reason.none")}
        </span>
        <span className="decision-run-card__facts">
          <span>{t("activity.runs.fact.verifier")} <strong>{run.falsificationVerdict ?? "—"}</strong></span>
          <span>{t("activity.runs.fact.gate")} <strong>{run.safetyRule ?? "—"}</strong></span>
          <span>{t("activity.runs.fact.orders")} <strong>{run.orderCount}</strong></span>
          <span>{t("activity.runs.fact.executions")} <strong>{run.executionCount}</strong></span>
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
    <aside className="decision-run-detail" role="dialog" aria-modal="false" aria-label={t("activity.runs.detail.aria")}>
      <header className="decision-run-detail__header">
        <span className="decision-run-detail__eyebrow">Decision run</span>
        {detail ? (
          <span className={`run-outcome run-outcome--${detail.summary.outcome.toLowerCase().replace("_", "-")}`}>
            {outcomeLabel(detail.summary.outcome as DecisionRunOutcomeFilter, t)}
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
  const { t } = useI18n();
  const decision = detail.decision;
  const intent = detail.intent;
  const safety = detail.safetyViolation;

  return (
    <>
      <DetailSection index="01" title={t("activity.runs.section.timeline")}>
        <ol className="run-phase-list">
          {detail.phases.map((phase) => (
            <li className="run-phase" data-state={phase.status.toLowerCase()} key={phase.key}>
              <span className="run-phase__rail"><span /></span>
              <span><strong>{phaseLabel(phase.key, t)}</strong><code>{phase.status}</code><small>{phase.detail ?? "—"}</small></span>
            </li>
          ))}
        </ol>
      </DetailSection>

      <DetailSection index="02" title={t("activity.runs.section.decision")}>
        <FactGrid facts={[
          [t("activity.runs.label.action"), decision?.action],
          [t("activity.runs.label.provider"), decision?.provider],
          ["p", decision?.estimatedWinProbability],
          ["roundTripCostR", decision?.roundTripCostR],
          [t("activity.runs.label.reason"), decision?.reasonJa, true],
        ]} />
      </DetailSection>

      {intent ? (
        <DetailSection index="03" title={t("activity.runs.section.intent")}>
          <FactGrid facts={[
            [t("activity.runs.label.order"), `${intent.side} ${intent.orderType}`],
            [t("activity.runs.label.size"), `${intent.sizeBtc} BTC`],
            [t("activity.runs.label.price"), intent.priceJpy],
            [t("activity.runs.label.stop"), intent.protectiveStopPriceJpy],
            [t("activity.runs.label.thesis"), intent.thesisJa, true],
          ]} />
        </DetailSection>
      ) : null}

      <DetailSection index="04" title={t("activity.runs.section.verification")}>
        <FactGrid facts={[
          [t("activity.runs.label.verdict"), detail.falsification?.verdict],
          [t("activity.runs.label.reason"), detail.falsification?.reasonJa, true],
        ]} />
      </DetailSection>

      <DetailSection index="05" title={t("activity.runs.section.safety")}>
        <div className="run-value-comparison">
          <div><small>{t("activity.runs.comparison.llm")}</small><strong>{decision?.expectedRMultiple ?? "—"}</strong><span>expectedR</span></div>
          <span aria-hidden="true">→</span>
          <div><small>{t("activity.runs.comparison.code")}</small><strong>{safety?.measuredValue ?? "—"}</strong><span>{safety ? `limit ${safety.limitValue}` : "—"}</span></div>
        </div>
        <FactGrid facts={[
          [t("activity.runs.label.rule"), safety?.rule],
          [t("activity.runs.label.message"), safety?.messageJa, true],
        ]} />
      </DetailSection>

      <DetailSection index="06" title={t("activity.runs.section.execution")}>
        <FactGrid facts={[
          [t("activity.runs.fact.orders"), String(detail.orders.length)],
          [t("activity.runs.fact.executions"), String(detail.executions.length)],
          [t("activity.runs.label.position"), detail.orders.map((order) => order.positionId).find(Boolean)],
          [t("activity.runs.label.tradeGroup"), detail.orders.map((order) => order.tradeGroupId).find(Boolean)],
          [t("activity.runs.label.entryReason"), detail.orders.map((order) => order.reasonJa).find(Boolean), true],
        ]} />
      </DetailSection>

      <DetailSection index="07" title={t("activity.runs.section.raw")}>
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

function RunListNotice({ text, action }: { text: string; action?: () => void }) {
  const { t } = useI18n();
  return (
    <div className="run-list-notice"><p>{text}</p>{action ? <button type="button" onClick={action}>{t("common.retry")}</button> : null}</div>
  );
}

function loadRunFilter(): DecisionRunOutcomeFilter {
  const saved = window.localStorage.getItem(DECISION_RUN_FILTER_STORAGE_KEY);
  return DECISION_RUN_OUTCOME_FILTERS.find((filter) => filter === saved) ?? "ALL";
}

function outcomeCount(outcome: DecisionRunOutcomeFilter, runs: OpsDecisionRunSummaryResponse[]): number {
  return outcome === "ALL" ? runs.length : runs.filter((run) => run.outcome === outcome).length;
}

function formatDuration(value: number | null | undefined): string {
  if (value == null) return "running";
  if (value < 1_000) return `${value}ms`;
  if (value < 60_000) return `${(value / 1_000).toFixed(1)}s`;
  return `${Math.floor(value / 60_000)}m ${Math.floor((value % 60_000) / 1_000)}s`;
}

type Translator = ReturnType<typeof useI18n>["t"];

function outcomeLabel(outcome: DecisionRunOutcomeFilter, t: Translator): string {
  const keys = {
    ALL: "activity.runs.filter.all",
    EXECUTED: "activity.runs.filter.executed",
    DENIED: "activity.runs.filter.denied",
    NO_TRADE: "activity.runs.filter.noTrade",
    INTERRUPTED: "activity.runs.filter.interrupted",
    RUNNING: "activity.runs.filter.running",
    FAILED: "activity.runs.filter.failed",
  } as const;
  return t(keys[outcome]);
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
