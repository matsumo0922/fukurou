import { useQuery, type UseQueryResult } from "@tanstack/react-query";
import RefreshCw from "lucide-react/dist/esm/icons/refresh-cw.mjs";
import { useEffect, useState } from "react";
import { systemStatusQuery, type SystemStatusSnapshot } from "../../api/system";
import { StatusPill, type StatusTone } from "../components/StatusPill";
import { describeError, formatTime, formatJstClock } from "../format";

export function TopStatusBar() {
  const statusQuery = useQuery(systemStatusQuery);
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
      <div className="top-status-bar__group" aria-label="System status">
        {renderSystemStatus(statusQuery)}
      </div>

      <div className="top-status-bar__group top-status-bar__group--right">
        <span className="top-status-bar__clock">{formatJstClock(currentClock)}</span>
        <button
          className="icon-text-button"
          type="button"
          onClick={() => void statusQuery.refetch()}
          disabled={statusQuery.isFetching}
        >
          <RefreshCw size={16} aria-hidden="true" />
          {statusQuery.isFetching ? "Refreshing" : "Refresh"}
        </button>
      </div>
    </header>
  );
}

function renderSystemStatus(statusQuery: UseQueryResult<SystemStatusSnapshot, Error>) {
  if (statusQuery.isPending) {
    return (
      <>
        <StatusPill label="Loading" tone="loading" />
        <span className="top-status-bar__text">Checking system endpoints</span>
      </>
    );
  }

  if (statusQuery.isError) {
    return (
      <>
        <StatusPill label="API error" tone="critical" />
        <span className="top-status-bar__text">{describeError(statusQuery.error)}</span>
      </>
    );
  }

  const readinessTone = readinessStatusTone(statusQuery.data.readiness.status);
  const freshnessTone = statusQuery.isStale ? "warning" : "positive";
  const freshnessLabel = statusQuery.isStale ? "Stale" : "Fresh";

  return (
    <>
      <StatusPill label={`health ${statusQuery.data.health.status}`} tone="positive" />
      <StatusPill label={`ready ${statusQuery.data.readiness.status}`} tone={readinessTone} />
      <StatusPill label={freshnessLabel} tone={freshnessTone} />
      <span className="top-status-bar__text">rev {statusQuery.data.revision}</span>
      <span className="top-status-bar__muted">updated {formatTime(statusQuery.data.fetchedAt)}</span>
    </>
  );
}

function readinessStatusTone(status: string): StatusTone {
  return status.toLowerCase() === "ready" ? "positive" : "warning";
}
