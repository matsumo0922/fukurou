import { useEffect, useState } from "react";
import { RefreshCw } from "lucide-react";
import { fetchRevision } from "./api/client";

type ApiCheckState =
  | {
      status: "loading";
    }
  | {
      status: "connected";
      revision: string;
      checkedAt: string;
    }
  | {
      status: "failed";
      message: string;
    };

const initialApiCheckState: ApiCheckState = {
  status: "loading",
};

export default function App() {
  const [apiCheckState, setApiCheckState] = useState<ApiCheckState>(initialApiCheckState);

  async function refreshApiCheck() {
    setApiCheckState({
      status: "loading",
    });

    setApiCheckState(await loadApiCheckState());
  }

  useEffect(() => {
    let isActive = true;

    void loadApiCheckState().then((nextApiCheckState) => {
      if (isActive) {
        setApiCheckState(nextApiCheckState);
      }
    });

    return () => {
      isActive = false;
    };
  }, []);

  const statusTone = apiCheckState.status === "connected" ? "positive" : apiCheckState.status;

  return (
    <main className="app-shell">
      <section className="connection-panel" aria-labelledby="app-title">
        <div className="panel-heading">
          <img className="brand-mark" src="/fukurou-mark.svg" alt="" aria-hidden="true" />
          <div>
            <p className="eyebrow">Local web foundation</p>
            <h1 id="app-title">Fukurou Web</h1>
          </div>
        </div>

        <div className="status-row">
          <span className={`status-indicator status-indicator--${statusTone}`} aria-hidden="true" />
          <div>
            <p className="status-label">{statusText(apiCheckState)}</p>
            <p className="status-detail">{statusDetail(apiCheckState)}</p>
          </div>
        </div>

        <dl className="endpoint-list">
          <div>
            <dt>Endpoint</dt>
            <dd>/revision</dd>
          </div>
          <div>
            <dt>Target</dt>
            <dd>Vite proxy to Ktor</dd>
          </div>
        </dl>

        <button className="refresh-button" type="button" onClick={() => void refreshApiCheck()}>
          <RefreshCw size={18} aria-hidden="true" />
          Refresh
        </button>
      </section>
    </main>
  );
}

async function loadApiCheckState(): Promise<ApiCheckState> {
  try {
    const revision = await fetchRevision();

    return {
      status: "connected",
      revision: revision.trim() || "unknown",
      checkedAt: new Date().toLocaleTimeString(),
    };
  } catch (error) {
    return {
      status: "failed",
      message: error instanceof Error ? error.message : "Unknown API error",
    };
  }
}

function statusText(apiCheckState: ApiCheckState): string {
  switch (apiCheckState.status) {
    case "connected":
      return "API connected";
    case "failed":
      return "API unavailable";
    case "loading":
      return "Checking API";
  }
}

function statusDetail(apiCheckState: ApiCheckState): string {
  switch (apiCheckState.status) {
    case "connected":
      return `revision ${apiCheckState.revision} at ${apiCheckState.checkedAt}`;
    case "failed":
      return apiCheckState.message;
    case "loading":
      return "Waiting for /revision";
  }
}
