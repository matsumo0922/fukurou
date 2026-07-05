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
import { EmptyState } from "../ui/components/EmptyState";
import { Panel } from "../ui/components/Panel";
import { SectionHeader } from "../ui/components/SectionHeader";
import { StatusPill, type StatusTone } from "../ui/components/StatusPill";
import { describeError, formatDateTime } from "../ui/format";
import { formatJpy, formatRatioAsPercent, formatSignedJpy } from "../ui/numberFormat";

export function ActivityPage() {
  const timelineQuery = useQuery(activityTimelineQuery);

  return (
    <div className="page-stack">
      <SectionHeader
        eyebrow="App"
        title="Activity"
        description="Decision, audit, and paper execution timeline."
        action={
          <button
            className="icon-text-button icon-text-button--prominent"
            type="button"
            onClick={() => void timelineQuery.refetch()}
            disabled={timelineQuery.isFetching}
          >
            <RefreshCw size={16} aria-hidden="true" />
            {timelineQuery.isFetching ? "Refreshing" : "Refresh"}
          </button>
        }
      />

      <Panel>
        <div className="panel-heading">
          <Activity size={18} aria-hidden="true" />
          <h2>Timeline</h2>
          {timelineQuery.data ? (
            <StatusPill label={timelineQuery.isStale ? "stale" : "fresh"} tone={timelineQuery.isStale ? "warning" : "positive"} />
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
  return (
    <div className="loading-row" role="status">
      <span className="loading-dot" aria-hidden="true" />
      <span>Loading activity timeline</span>
    </div>
  );
}

function ActivityError({ error, retried }: { error: unknown; retried: () => void }) {
  return (
    <EmptyState
      title="Activity timeline unavailable"
      description={describeError(error)}
      action={
        <button className="icon-text-button" type="button" onClick={retried}>
          <RefreshCw size={16} aria-hidden="true" />
          Retry
        </button>
      }
    />
  );
}

function ActivityTimeline({ timeline }: { timeline: ActivityTimelineSnapshot }) {
  const { fetchedAt, limits } = timeline;
  const events = newestFirstActivityTimelineEvents(timeline.events);

  if (events.length === 0) {
    return <EmptyState title="No activity recorded" description="The decision, audit, and execution feeds are empty." />;
  }

  return (
    <>
      <p className="timeline__freshness">
        Updated {formatDateTime(fetchedAt)} · newest first · {events.length}/{limits.total} records · decisions{" "}
        {limits.decisions} / audit {limits.audit} / executions {limits.executions}
      </p>
      <ol className="timeline" aria-label="Activity timeline">
        {events.map((event) => (
          <li className="timeline__item" key={event.id}>
            <div className="timeline__rail" aria-hidden="true" />
            <div className="timeline__body">
              <div className="timeline__header">
                <StatusPill label={sourceLabel(event.source)} tone={sourceTone(event.source)} />
                <StatusPill label={event.kind} tone={eventTone(event)} />
                <time dateTime={event.occurredAt}>{formatDateTime(event.occurredAt)}</time>
              </div>
              <h2>{event.title}</h2>
              <p>{formatTimelineDetail(event)}</p>
              <dl className="timeline__metadata">
                {event.metadata.map((item) => (
                  <div key={item.label}>
                    <dt>{item.label}</dt>
                    <dd>{formatMetadataValue(item.label, item.value)}</dd>
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

function sourceLabel(source: ActivityTimelineSource): string {
  switch (source) {
    case "audit":
      return "audit";
    case "decision":
      return "decision";
    case "execution":
      return "execution";
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

function formatTimelineDetail(event: ActivityTimelineEvent): string {
  if (event.source !== "execution") {
    return event.detail;
  }

  const [sizeBtc, priceJpy] = event.detail.split(" BTC at ");

  if (!priceJpy) {
    return event.detail;
  }

  return `${sizeBtc} BTC at ${formatJpy(priceJpy.replace(" JPY", ""))}`;
}

function formatMetadataValue(label: string, value: string): string {
  if (label === "estimated p") {
    return formatRatioAsPercent(value);
  }

  if (label === "realized pnl") {
    return formatSignedJpy(value);
  }

  if (label === "fee") {
    return formatJpy(value);
  }

  return value;
}
