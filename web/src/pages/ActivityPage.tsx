import { useQuery } from "@tanstack/react-query";
import Activity from "lucide-react/dist/esm/icons/activity.mjs";
import RefreshCw from "lucide-react/dist/esm/icons/refresh-cw.mjs";
import {
  activityTimelineQuery,
  newestFirstActivityTimelineEvents,
  type ActivityTimelineEvent,
  type ActivityTimelineSnapshot,
  type ActivityTimelineSource,
} from "../api/ops";
import type { MessageKey } from "../i18n/messages";
import { useI18n } from "../i18n/useI18n";
import { EmptyState } from "../ui/components/EmptyState";
import { Panel } from "../ui/components/Panel";
import { SectionHeader } from "../ui/components/SectionHeader";
import { StatusPill, type StatusTone } from "../ui/components/StatusPill";
import { describeError, formatDateTime } from "../ui/format";
import { formatJpy, formatRatioAsPercent, formatSignedJpy } from "../ui/numberFormat";

export function ActivityPage() {
  const timelineQuery = useQuery(activityTimelineQuery);
  const { t } = useI18n();

  return (
    <div className="page-stack">
      <SectionHeader
        eyebrow="App"
        title="Activity"
        description={t("activity.description")}
        action={
          <button
            className="icon-text-button icon-text-button--prominent"
            type="button"
            onClick={() => void timelineQuery.refetch()}
            disabled={timelineQuery.isFetching}
          >
            <RefreshCw size={16} aria-hidden="true" />
            {timelineQuery.isFetching ? t("common.refreshing") : t("common.refresh")}
          </button>
        }
      />

      <Panel>
        <div className="panel-heading">
          <Activity size={18} aria-hidden="true" />
          <h2>{t("activity.panel.timeline")}</h2>
          {timelineQuery.data ? (
            <StatusPill label={timelineQuery.isStale ? t("common.stale") : t("common.fresh")} tone={timelineQuery.isStale ? "warning" : "positive"} />
          ) : null}
        </div>
        {timelineQuery.isPending ? <ActivityLoading /> : null}
        {timelineQuery.isError ? <ActivityError error={timelineQuery.error} retried={() => void timelineQuery.refetch()} /> : null}
        {timelineQuery.data ? <ActivityTimeline timeline={timelineQuery.data} /> : null}
      </Panel>
    </div>
  );
}

function ActivityLoading() {
  const { t } = useI18n();

  return (
    <div className="loading-row" role="status">
      <span className="loading-dot" aria-hidden="true" />
      <span>{t("activity.loading.timeline")}</span>
    </div>
  );
}

function ActivityError({ error, retried }: { error: unknown; retried: () => void }) {
  const { locale, t } = useI18n();

  return (
    <EmptyState
      title={t("activity.error.timeline")}
      description={describeError(error, locale)}
      action={
        <button className="icon-text-button" type="button" onClick={retried}>
          <RefreshCw size={16} aria-hidden="true" />
          {t("common.retry")}
        </button>
      }
    />
  );
}

function ActivityTimeline({ timeline }: { timeline: ActivityTimelineSnapshot }) {
  const { locale, t } = useI18n();
  const { fetchedAt, limits } = timeline;
  const events = newestFirstActivityTimelineEvents(timeline.events);

  if (events.length === 0) {
    return <EmptyState title={t("activity.empty.title")} description={t("activity.empty.description")} />;
  }

  return (
    <>
      <p className="timeline__freshness">
        {t("activity.updated")} {formatDateTime(fetchedAt, locale)} · {t("activity.newestFirst")} · {events.length}/{limits.total}{" "}
        {t("activity.records")} · {t("activity.decisions")} {limits.decisions} / {t("activity.audit")} {limits.audit} /{" "}
        {t("activity.executions")} {limits.executions}
      </p>
      <ol className="timeline" aria-label={t("activity.timelineAria")}>
        {events.map((event) => (
          <li className="timeline__item" key={event.id}>
            <div className="timeline__rail" aria-hidden="true" />
            <div className="timeline__body">
              <div className="timeline__header">
                <StatusPill label={sourceLabel(event.source, t)} tone={sourceTone(event.source)} />
                <StatusPill label={event.kind} tone={eventTone(event)} />
                <time dateTime={event.occurredAt}>{formatDateTime(event.occurredAt, locale)}</time>
              </div>
              <h2>{event.title}</h2>
              <p>{formatTimelineDetail(event, t)}</p>
              <dl className="timeline__metadata">
                {event.metadata.map((item) => (
                  <div key={item.label}>
                    <dt>{metadataLabel(item.label, t)}</dt>
                    <dd>{formatMetadataValue(item.label, item.value, t)}</dd>
                  </div>
                ))}
              </dl>
            </div>
          </li>
        ))}
      </ol>
    </>
  );
}

function sourceLabel(source: ActivityTimelineSource, t: (key: MessageKey) => string): string {
  switch (source) {
    case "audit":
      return t("activity.source.audit");
    case "decision":
      return t("activity.source.decision");
    case "execution":
      return t("activity.source.execution");
  }
}

function sourceTone(source: ActivityTimelineSource): StatusTone {
  switch (source) {
    case "audit":
      return "neutral";
    case "decision":
      return "warning";
    case "execution":
      return "positive";
  }
}

function eventTone(event: ActivityTimelineEvent): StatusTone {
  if (event.source === "audit" && event.kind.includes("HARD_HALT")) {
    return "critical";
  }

  if (event.source === "audit" && event.kind.includes("SOFT_HALT")) {
    return "warning";
  }

  if (event.source === "decision" && event.kind === "NO_TRADE") {
    return "neutral";
  }

  return event.source === "execution" ? "positive" : "warning";
}

function formatTimelineDetail(event: ActivityTimelineEvent, t: (key: MessageKey) => string): string {
  if (event.source !== "execution") {
    return event.detail;
  }

  const [sizeBtc, priceJpy] = event.detail.split(" BTC at ");

  if (!priceJpy) {
    return event.detail;
  }

  return `${sizeBtc} BTC ${t("activity.executionPriceJoin")} ${formatJpy(priceJpy.replace(" JPY", ""))}`;
}

function metadataLabel(label: string, t: (key: MessageKey) => string): string {
  switch (label) {
    case "estimated p":
      return t("activity.label.estimatedP");
    case "setup tags":
      return t("activity.label.setupTags");
    case "no-trade conditions":
      return t("activity.label.noTradeConditions");
    case "tool":
      return t("activity.label.tool");
    case "realized pnl":
      return t("activity.label.realizedPnl");
    case "fee":
      return t("activity.label.fee");
    case "liquidity":
      return t("activity.label.liquidity");
    case "order":
      return t("activity.label.order");
    default:
      return label;
  }
}

function formatMetadataValue(label: string, value: string, t: (key: MessageKey) => string): string {
  if (label === "estimated p") {
    return formatRatioAsPercent(value);
  }

  if (label === "realized pnl") {
    return formatSignedJpy(value);
  }

  if (label === "fee") {
    return formatJpy(value);
  }

  if (label === "order" && value === "not linked") {
    return t("activity.notLinked");
  }

  return value;
}
