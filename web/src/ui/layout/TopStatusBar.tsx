import { useQuery, type UseQueryResult } from "@tanstack/react-query";
import RefreshCw from "lucide-react/dist/esm/icons/refresh-cw.mjs";
import { useEffect, useState } from "react";
import { systemStatusQuery, type SystemStatusSnapshot } from "../../api/system";
import { locales, type Locale, type MessageKey } from "../../i18n/messages";
import { useI18n } from "../../i18n/useI18n";
import { StatusPill, type StatusTone } from "../components/StatusPill";
import { describeError, formatClock, formatTime } from "../format";

export function TopStatusBar() {
  const statusQuery = useQuery(systemStatusQuery);
  const { locale, setLocale, t } = useI18n();
  const [currentClock, setCurrentClock] = useState(() => new Date());

  useEffect(() => {
    const intervalId = window.setInterval(() => {
      setCurrentClock(new Date());
    }, 1_000);

    return () => {
      window.clearInterval(intervalId);
    };
  }, []);

  return (
    <header className="top-status-bar">
      <div className="top-status-bar__group" aria-label={t("topStatus.systemStatus")}>
        {renderSystemStatus(statusQuery, locale, t)}
      </div>

      <div className="top-status-bar__group top-status-bar__group--right">
        <LanguageSwitch locale={locale} changed={setLocale} label={t("common.language")} />
        <span className="top-status-bar__clock">{formatClock(currentClock, locale)}</span>
        <button
          className="icon-text-button"
          type="button"
          onClick={() => void statusQuery.refetch()}
          disabled={statusQuery.isFetching}
        >
          <RefreshCw size={16} aria-hidden="true" />
          {statusQuery.isFetching ? t("common.refreshing") : t("common.refresh")}
        </button>
      </div>
    </header>
  );
}

function LanguageSwitch({
  locale,
  changed,
  label,
}: {
  locale: Locale;
  changed: (locale: Locale) => void;
  label: string;
}) {
  return (
    <div className="language-toggle" role="group" aria-label={label}>
      {locales.map((option) => (
        <button
          className={["language-toggle__button", option === locale ? "language-toggle__button--active" : null].filter(Boolean).join(" ")}
          type="button"
          key={option}
          aria-pressed={option === locale}
          onClick={() => changed(option)}
        >
          {option.toUpperCase()}
        </button>
      ))}
    </div>
  );
}

function renderSystemStatus(
  statusQuery: UseQueryResult<SystemStatusSnapshot, Error>,
  locale: Locale,
  t: (key: MessageKey) => string,
) {
  if (statusQuery.isPending) {
    return (
      <>
        <StatusPill label={t("topStatus.loading")} tone="loading" />
        <span className="top-status-bar__text">{t("topStatus.checking")}</span>
      </>
    );
  }

  if (statusQuery.isError) {
    return (
      <>
        <StatusPill label={t("topStatus.apiError")} tone="critical" />
        <span className="top-status-bar__text">{describeError(statusQuery.error, locale)}</span>
      </>
    );
  }

  const readinessTone = readinessStatusTone(statusQuery.data.readiness.status);
  const freshnessTone = statusQuery.isStale ? "warning" : "positive";
  const freshnessLabel = statusQuery.isStale ? t("common.stale") : t("common.fresh");

  return (
    <>
      <StatusPill label={`health ${statusQuery.data.health.status}`} tone="positive" />
      <StatusPill label={`ready ${statusQuery.data.readiness.status}`} tone={readinessTone} />
      <StatusPill
        label={`market ${statusQuery.data.readiness.marketDataState ?? "DISCONNECTED"}`}
        tone={statusQuery.data.readiness.marketDataState === "CONNECTED" ? "positive" : "critical"}
      />
      <StatusPill label={freshnessLabel} tone={freshnessTone} />
      <span className="top-status-bar__text">
        {t("topStatus.revisionPrefix")} {statusQuery.data.revision}
      </span>
      <span className="top-status-bar__muted">
        {t("topStatus.updatedPrefix")} {formatTime(statusQuery.data.fetchedAt, locale)}
      </span>
    </>
  );
}

function readinessStatusTone(status: string): StatusTone {
  return status.toLowerCase() === "ready" ? "positive" : "warning";
}
