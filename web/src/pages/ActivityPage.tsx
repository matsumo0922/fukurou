import { useEffect, useMemo, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import Activity from "lucide-react/dist/esm/icons/activity.mjs";
import ChevronDown from "lucide-react/dist/esm/icons/chevron-down.mjs";
import Filter from "lucide-react/dist/esm/icons/filter.mjs";
import RefreshCw from "lucide-react/dist/esm/icons/refresh-cw.mjs";
import {
  ACTIVITY_AUDIT_EVENT_TYPES,
  ACTIVITY_TIMELINE_FILTER_STORAGE_KEY,
  ACTIVITY_TIMELINE_SOURCE_FILTERS,
  DEFAULT_ACTIVITY_TIMELINE_FILTERS,
  activityTimelineQuery,
  fetchActivityTimeline,
  newestFirstActivityTimelineEvents,
  normalizeActivityTimelineFilters,
  type ActivityTimelineEvent,
  type ActivityTimelineFilters,
  type ActivityTimelineSnapshot,
  type ActivityTimelineSource,
  type ActivityTimelineSourceFilter,
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
  const [filters, setFilters] = useState(loadStoredActivityTimelineFilters);
  const [olderPages, setOlderPages] = useState<ActivityTimelineSnapshot[]>([]);
  const [olderError, setOlderError] = useState<unknown>(null);
  const [isLoadingOlder, setIsLoadingOlder] = useState(false);
  const hasLoadedOlderPages = olderPages.length > 0;
  const timelineQuery = useQuery(activityTimelineQuery(filters, undefined, !hasLoadedOlderPages));
  const visibleTimeline = useMemo(
    () => mergeActivityTimelinePages(timelineQuery.data ?? null, olderPages),
    [olderPages, timelineQuery.data],
  );
  const { t } = useI18n();

  useEffect(() => {
    persistActivityTimelineFilters(filters);
  }, [filters]);

  const refreshed = () => {
    setOlderPages([]);
    setOlderError(null);
    void timelineQuery.refetch();
  };
  const filtersChanged = (changedFilters: ActivityTimelineFilters) => {
    setFilters(changedFilters);
    setOlderPages([]);
    setOlderError(null);
  };
  const olderLoaded = () => {
    void loadOlderActivityPage({
      filters,
      latestPage: timelineQuery.data ?? null,
      olderPages,
      setOlderPages,
      setOlderError,
      setIsLoadingOlder,
    });
  };

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
            onClick={refreshed}
            disabled={timelineQuery.isFetching}
          >
            <RefreshCw size={16} aria-hidden="true" />
            {timelineQuery.isFetching ? t("common.refreshing") : t("common.refresh")}
          </button>
        }
      />

      <Panel>
        <ActivityFilters filters={filters} filtersChanged={filtersChanged} />
      </Panel>

      <Panel>
        <div className="panel-heading">
          <Activity size={18} aria-hidden="true" />
          <h2>{t("activity.panel.timeline")}</h2>
          {visibleTimeline ? (
            <StatusPill
              label={timelineQuery.isStale ? t("common.stale") : t("common.fresh")}
              tone={timelineQuery.isStale ? "warning" : "positive"}
            />
          ) : null}
        </div>
        {timelineQuery.isPending && !visibleTimeline ? <ActivityLoading /> : null}
        {timelineQuery.isError && !visibleTimeline ? (
          <ActivityError error={timelineQuery.error} retried={() => void timelineQuery.refetch()} />
        ) : null}
        {visibleTimeline ? (
          <ActivityTimeline
            timeline={visibleTimeline}
            loadedPageCount={1 + olderPages.length}
            olderError={olderError}
            isLoadingOlder={isLoadingOlder}
            olderLoaded={olderLoaded}
          />
        ) : null}
      </Panel>
    </div>
  );
}

function ActivityFilters({
  filters,
  filtersChanged,
}: {
  filters: ActivityTimelineFilters;
  filtersChanged: (filters: ActivityTimelineFilters) => void;
}) {
  const { t } = useI18n();

  return (
    <div className="activity-filters" aria-label={t("activity.filters.aria")}>
      <div className="activity-filters__heading">
        <Filter size={18} aria-hidden="true" />
        <h2>{t("activity.filters.title")}</h2>
      </div>
      <div className="activity-filters__section">
        <span className="activity-filters__label">{t("activity.filters.source")}</span>
        <div className="segmented-control" role="group" aria-label={t("activity.filters.source")}>
          {ACTIVITY_TIMELINE_SOURCE_FILTERS.map((source) => (
            <button
              className={
                source === filters.source
                  ? "segmented-control__button segmented-control__button--active"
                  : "segmented-control__button"
              }
              type="button"
              aria-pressed={source === filters.source}
              key={source}
              onClick={() => filtersChanged({ ...filters, source })}
            >
              {sourceFilterLabel(source, t)}
            </button>
          ))}
        </div>
      </div>
      <fieldset className="activity-event-filter">
        <legend>{t("activity.filters.auditEventTypes")}</legend>
        <div className="activity-event-filter__options">
          {ACTIVITY_AUDIT_EVENT_TYPES.map((eventType) => (
            <label className="checkbox-chip" key={eventType}>
              <input
                type="checkbox"
                checked={filters.auditEventTypes.includes(eventType)}
                onChange={() => filtersChanged(toggleAuditEventType(filters, eventType))}
              />
              <span>{eventType}</span>
            </label>
          ))}
        </div>
      </fieldset>
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

function ActivityTimeline({
  timeline,
  loadedPageCount,
  olderError,
  isLoadingOlder,
  olderLoaded,
}: {
  timeline: ActivityTimelineSnapshot;
  loadedPageCount: number;
  olderError: unknown;
  isLoadingOlder: boolean;
  olderLoaded: () => void;
}) {
  const { locale, t } = useI18n();
  const events = newestFirstActivityTimelineEvents(timeline.events);
  const canLoadOlder = timeline.nextBefore !== null;

  if (events.length === 0) {
    return <EmptyState title={t("activity.empty.title")} description={t("activity.empty.description")} />;
  }

  return (
    <>
      <p className="timeline__freshness">
        {t("activity.updated")} {formatDateTime(timeline.fetchedAt, locale)} · {t("activity.newestFirst")} · {events.length}/
        {timeline.limit} {t("activity.records")} · {t("activity.loadedPages")} {loadedPageCount}
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
      <div className="timeline__paging">
        <button className="icon-text-button" type="button" onClick={olderLoaded} disabled={!canLoadOlder || isLoadingOlder}>
          <ChevronDown size={16} aria-hidden="true" />
          {isLoadingOlder ? t("activity.loadingOlder") : t("activity.loadOlder")}
        </button>
        {!canLoadOlder ? <span>{t("activity.noOlderRecords")}</span> : null}
      </div>
      {olderError ? <p className="timeline__paging-error">{describeError(olderError, locale)}</p> : null}
    </>
  );
}

function sourceFilterLabel(source: ActivityTimelineSourceFilter, t: (key: MessageKey) => string): string {
  switch (source) {
    case "all":
      return t("activity.filter.all");
    case "audit":
      return t("activity.source.audit");
    case "decision":
      return t("activity.source.decision");
    case "execution":
      return t("activity.source.execution");
  }
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

function mergeActivityTimelinePages(
  latestPage: ActivityTimelineSnapshot | null,
  olderPages: ActivityTimelineSnapshot[],
): ActivityTimelineSnapshot | null {
  const lastOlderPage = olderPages[olderPages.length - 1];
  const lastPage = lastOlderPage ?? latestPage;

  if (!latestPage || !lastPage) {
    return null;
  }

  return {
    ...latestPage,
    events: newestFirstActivityTimelineEvents([latestPage, ...olderPages].flatMap((page) => page.events)),
    nextBefore: lastPage.nextBefore,
  };
}

async function loadOlderActivityPage({
  filters,
  latestPage,
  olderPages,
  setOlderPages,
  setOlderError,
  setIsLoadingOlder,
}: {
  filters: ActivityTimelineFilters;
  latestPage: ActivityTimelineSnapshot | null;
  olderPages: ActivityTimelineSnapshot[];
  setOlderPages: (pages: ActivityTimelineSnapshot[]) => void;
  setOlderError: (error: unknown) => void;
  setIsLoadingOlder: (isLoading: boolean) => void;
}) {
  const lastOlderPage = olderPages[olderPages.length - 1];
  const lastPage = lastOlderPage ?? latestPage;
  const before = lastPage?.nextBefore;

  if (!before) {
    return;
  }

  setOlderError(null);
  setIsLoadingOlder(true);

  try {
    const olderPage = await fetchActivityTimeline({ filters, before });
    setOlderPages([...olderPages, olderPage]);
  } catch (error) {
    setOlderError(error);
  } finally {
    setIsLoadingOlder(false);
  }
}

function toggleAuditEventType(filters: ActivityTimelineFilters, eventType: string): ActivityTimelineFilters {
  const selectedEventTypes = new Set(filters.auditEventTypes);

  if (selectedEventTypes.has(eventType)) {
    selectedEventTypes.delete(eventType);
  } else {
    selectedEventTypes.add(eventType);
  }

  return {
    ...filters,
    auditEventTypes: ACTIVITY_AUDIT_EVENT_TYPES.filter((candidate) => selectedEventTypes.has(candidate)),
  };
}

function loadStoredActivityTimelineFilters(): ActivityTimelineFilters {
  try {
    const storedValue = window.localStorage.getItem(ACTIVITY_TIMELINE_FILTER_STORAGE_KEY);
    const parsedValue = storedValue ? JSON.parse(storedValue) : null;

    return normalizeActivityTimelineFilters(parsedValue);
  } catch {
    return DEFAULT_ACTIVITY_TIMELINE_FILTERS;
  }
}

function persistActivityTimelineFilters(filters: ActivityTimelineFilters) {
  try {
    window.localStorage.setItem(ACTIVITY_TIMELINE_FILTER_STORAGE_KEY, JSON.stringify(filters));
  } catch {
    return;
  }
}
