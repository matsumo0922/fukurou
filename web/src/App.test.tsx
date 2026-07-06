import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import App from "./App";

describe("App", () => {
  afterEach(() => {
    cleanup();
    vi.useRealTimers();
    vi.unstubAllGlobals();
    window.history.replaceState({}, "", "/");
  });

  it("redirects root to overview and shows only implemented routes", async () => {
    stubSystemFetch();
    window.history.pushState({}, "", "/");

    render(<App />);

    await waitFor(() => {
      expect(window.location.pathname).toBe("/app/overview");
    });
    expect(screen.getByRole("heading", { name: "Overview" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /Overview/ })).toHaveAttribute("href", "/app/overview");
    expect(screen.getByRole("link", { name: /Activity/ })).toHaveAttribute("href", "/app/activity");
    expect(screen.getByRole("link", { name: /System/ })).toHaveAttribute("href", "/app/system");
    expect(screen.queryByText("Evaluation")).not.toBeInTheDocument();
    expect(screen.queryByText("Controls")).not.toBeInTheDocument();
    expect(screen.queryByText("Notes")).not.toBeInTheDocument();
  });

  it("redirects unimplemented app deep links to overview", async () => {
    stubSystemFetch();
    window.history.pushState({}, "", "/app/evaluation");

    render(<App />);

    await waitFor(() => {
      expect(window.location.pathname).toBe("/app/overview");
    });
    expect(screen.getByRole("heading", { name: "Overview" })).toBeInTheDocument();
  });

  it("shows system endpoint data from the real health and revision routes", async () => {
    stubSystemFetch({
      health: {
        status: "ok",
        service: "fukurou",
      },
      readiness: {
        status: "ready",
        lastReconciledAt: "2026-07-05T12:00:00.000Z",
        lastMarketDataAt: null,
      },
      revision: "local-sha",
    });
    window.history.pushState({}, "", "/app/system");

    render(<App />);

    expect(await screen.findByText("GET /health")).toBeInTheDocument();
    expect(screen.getByText("GET /health/ready")).toBeInTheDocument();
    expect(screen.getByText("GET /revision")).toBeInTheDocument();
    expect(screen.getByText("status=ok")).toBeInTheDocument();
    expect(screen.getByText("status=ready")).toBeInTheDocument();
    expect(screen.getAllByText("local-sha").length).toBeGreaterThan(0);
    expect(screen.getAllByText(/JST/).length).toBeGreaterThan(1);
  });

  it("shows overview operations data from read APIs", async () => {
    stubSystemFetch();
    window.history.pushState({}, "", "/app/overview");

    render(<App />);

    expect(await screen.findByRole("heading", { name: "Risk state" })).toBeInTheDocument();
    expect(screen.getAllByText("RUNNING").length).toBeGreaterThan(0);
    expect(screen.getAllByText("NO_TRADE").length).toBeGreaterThan(0);
    expect(screen.getByText("¥195,000")).toBeInTheDocument();
    expect(screen.getByText("within bounds")).toBeInTheDocument();
  });

  it("shows merged activity timeline records newest first from bounded feeds", async () => {
    const fetchMock = stubSystemFetch();
    window.history.pushState({}, "", "/app/activity");

    render(<App />);

    expect(await screen.findByText("BUY BTC execution")).toBeInTheDocument();
    expect(screen.getByText("NO_TRADE decision")).toBeInTheDocument();
    expect(screen.getAllByText("MANUAL_RESUME_REQUESTED").length).toBeGreaterThan(0);
    expect(screen.getAllByText("operator").length).toBeGreaterThan(0);
    expect(screen.getByText(/newest first/)).toBeInTheDocument();
    expect(screen.getByText(/3\/90 records/)).toBeInTheDocument();

    const timeline = screen.getByRole("list", { name: "Activity timeline" });
    const timelineItems = within(timeline).getAllByRole("listitem");

    expect(timelineItems.map((item) => within(item).getByRole("heading", { level: 2 }).textContent)).toEqual([
      "BUY BTC execution",
      "MANUAL_RESUME_REQUESTED",
      "NO_TRADE decision",
    ]);
    expect(fetchMock).toHaveBeenCalledWith(
      "/ops/decisions?limit=20",
      expect.objectContaining({ method: "GET" }),
    );
    expect(fetchMock).toHaveBeenCalledWith(
      "/ops/audit?limit=50&excludeEventType=RECONCILER_PASS_COMPLETED",
      expect.objectContaining({ method: "GET" }),
    );
    expect(fetchMock).toHaveBeenCalledWith(
      "/ops/executions?limit=20",
      expect.objectContaining({ method: "GET" }),
    );
  });

  it("shows a loading state while system endpoints are pending", () => {
    vi.stubGlobal("fetch", vi.fn(() => new Promise<Response>(() => undefined)));
    window.history.pushState({}, "", "/app/system");

    render(<App />);

    expect(screen.getByRole("status")).toHaveTextContent("Loading system status");
  });

  it("shows a user-facing error when system endpoints fail", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => jsonResponse({ message: "service unavailable" }, { status: 500 })),
    );
    window.history.pushState({}, "", "/app/system");

    render(<App />);

    expect(await screen.findByText("System data unavailable")).toBeInTheDocument();
    expect(screen.getAllByText("GET /health failed with status 500").length).toBeGreaterThan(0);
  });
});

type SystemFetchFixture = {
  health?: {
    status: string;
    service?: string;
  };
  readiness?: {
    status: string;
    lastReconciledAt?: string | null;
    lastMarketDataAt?: string | null;
  };
  revision?: string;
  readinessStatus?: number;
};

function stubSystemFetch(fixture: SystemFetchFixture = {}) {
  const health = fixture.health ?? {
    status: "ok",
    service: "fukurou",
  };
  const readiness = fixture.readiness ?? {
    status: "ready",
    lastReconciledAt: null,
    lastMarketDataAt: null,
  };
  const revision = fixture.revision ?? "test-sha";
  const readinessStatus = fixture.readinessStatus ?? 200;

  const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
    const path = requestPath(input);

    switch (path) {
      case "/health":
        return jsonResponse(health);
      case "/health/ready":
        return jsonResponse(readiness, { status: readinessStatus });
      case "/revision":
        return new Response(revision, {
          status: 200,
          headers: {
            "Content-Type": "text/plain",
          },
        });
      case "/ops/risk-state":
        return jsonResponse({
          state: "RUNNING",
          haltReason: null,
          haltAt: null,
          resumedAt: null,
          resumedReason: null,
          drawdownRatio: "0.01",
        });
      case "/ops/account":
        return jsonResponse({
          mode: "PAPER",
          cashJpy: "94000",
          initialCashJpy: "100000",
          btcQuantity: "0.01000000",
          btcMarkPriceJpy: "10100000",
          totalEquityJpy: "195000",
          equityPeakJpy: "200000",
          drawdownRatio: "0.025",
          updatedAt: "2026-07-05T12:01:00.000Z",
        });
      case "/ops/decisions":
        return jsonResponse({
          decisions: [
            {
              id: "decision-1",
              action: "NO_TRADE",
              setupTags: ["range"],
              estimatedWinProbability: "0.42",
              reasonJa: "条件が揃うまで待機します。",
              noTradeConditionsJa: ["ボラティリティ不足"],
              createdAt: "2026-07-05T12:02:00.000Z",
            },
          ],
        });
      case "/ops/positions":
        return jsonResponse({
          positions: [],
          openOrders: [],
        });
      case "/ops/executions":
        return jsonResponse({
          executions: [
            {
              executionId: "execution-1",
              orderId: "order-1",
              positionId: "position-1",
              mode: "PAPER",
              symbol: "BTC",
              side: "BUY",
              priceJpy: "10000000",
              sizeBtc: "0.01000000",
              feeJpy: "10",
              realizedPnlJpy: "0",
              liquidity: "TAKER",
              executedAt: "2026-07-05T12:04:00.000Z",
            },
          ],
        });
      case "/ops/audit":
        return jsonResponse({
          events: [
            {
              id: "event-1",
              eventType: "MANUAL_RESUME_REQUESTED",
              toolName: "operator",
              payload: "{}",
              occurredAt: "2026-07-05T12:03:00.000Z",
            },
          ],
        });
      case "/evaluation/summary":
        return jsonResponse({
          period: {
            from: "2026-06-05",
            to: "2026-07-05",
            timezone: "Asia/Tokyo",
          },
          truncated: false,
          performance: {
            tradeCount: 3,
            totalPnlJpy: "1200",
            profitFactor: "1.5",
            winRate: "0.66",
            expectedR: "0.2",
            averageMaeR: "0.1",
            averageMfeR: "0.3",
            rUnavailableCount: 0,
            maeUnavailableCount: 0,
            mfeUnavailableCount: 0,
          },
          killCriterion: {
            closedTrades: 3,
            currentProfitFactor: "1.5",
            minClosedTrades: 20,
            minProfitFactor: "1.1",
            remainingTrades: 17,
            breached: false,
            hardHalt: false,
          },
          runRates: {
            decisionRunCount: 5,
            actionCounts: [
              {
                action: "NO_TRADE",
                count: 4,
              },
            ],
            entryRate: "0.2",
            noTradeRate: "0.8",
          },
          marketRegimes: [],
        });
      default:
        return jsonResponse({ message: "not found" }, { status: 404 });
    }
  });

  vi.stubGlobal("fetch", fetchMock);

  return fetchMock;
}

function requestPath(input: RequestInfo | URL): string {
  if (input instanceof Request) {
    return new URL(input.url).pathname;
  }

  if (input instanceof URL) {
    return input.pathname;
  }

  return new URL(input, "http://localhost").pathname;
}

function jsonResponse(body: unknown, init: ResponseInit = {}): Response {
  return new Response(JSON.stringify(body), {
    ...init,
    headers: {
      "Content-Type": "application/json",
      ...init.headers,
    },
  });
}
