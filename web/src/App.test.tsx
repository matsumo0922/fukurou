import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import App from "./App";

describe("App", () => {
  afterEach(() => {
    cleanup();
    vi.useRealTimers();
    vi.unstubAllGlobals();
    window.history.replaceState({}, "", "/");
  });

  it("redirects root to overview and shows implemented routes", async () => {
    stubSystemFetch();
    window.history.pushState({}, "", "/");

    render(<App />);

    await waitFor(() => {
      expect(window.location.pathname).toBe("/app/overview");
    });
    expect(screen.getByRole("heading", { name: "Overview" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /Overview/ })).toHaveAttribute("href", "/app/overview");
    expect(screen.getByRole("link", { name: /Activity/ })).toHaveAttribute("href", "/app/activity");
    expect(screen.getByRole("link", { name: /Controls/ })).toHaveAttribute("href", "/app/controls");
    expect(screen.getByRole("link", { name: /Evaluation/ })).toHaveAttribute("href", "/app/evaluation");
    expect(screen.getByRole("link", { name: /System/ })).toHaveAttribute("href", "/app/system");
    expect(screen.queryByText("Notes")).not.toBeInTheDocument();
  });

  it("redirects unimplemented app deep links to overview", async () => {
    stubSystemFetch();
    window.history.pushState({}, "", "/app/notes");

    render(<App />);

    await waitFor(() => {
      expect(window.location.pathname).toBe("/app/overview");
    });
    expect(screen.getByRole("heading", { name: "Overview" })).toBeInTheDocument();
  });

  it("routes to controls and keeps operations out of read-only overview", async () => {
    stubSystemFetch();
    window.history.pushState({}, "", "/app/controls");

    render(<App />);

    expect(await screen.findByRole("heading", { name: "Controls" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /Controls/ })).toHaveAttribute("href", "/app/controls");
    expect(screen.getByRole("heading", { name: "Halt controls" })).toBeInTheDocument();
    expect(screen.getByText(/New entry decisions are rejected/)).toBeInTheDocument();

    window.history.pushState({}, "", "/app/overview");
    cleanup();
    stubSystemFetch();
    render(<App />);

    expect(await screen.findByRole("heading", { name: "Overview" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Review SOFT_HALT" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Review manual trigger" })).not.toBeInTheDocument();
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

  it("shows evaluation data from the read APIs", async () => {
    const fetchMock = stubSystemFetch();
    window.history.pushState({}, "", "/app/evaluation");

    render(<App />);

    expect(await screen.findByRole("heading", { name: "Evaluation" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /Evaluation/ })).toHaveAttribute("href", "/app/evaluation");
    expect((await screen.findAllByText("trend-breakout")).length).toBeGreaterThan(0);
    expect(screen.getByRole("heading", { name: "Setup performance" })).toBeInTheDocument();
    expect(screen.getAllByText("claude").length).toBeGreaterThan(0);
    expect(screen.getByText("claude-sonnet-4")).toBeInTheDocument();
    expect(screen.getByText("Bot realized")).toBeInTheDocument();
    expect(screen.getAllByText("¥101,000").length).toBeGreaterThan(0);
    expect(screen.getByText("latest 6 of 7")).toBeInTheDocument();
    expect(screen.getAllByText("2.88%").length).toBeGreaterThan(0);
    expect(screen.getAllByText("$0.1234").length).toBeGreaterThan(0);

    expect(fetchMock).toHaveBeenCalledWith(
      "/evaluation/summary",
      expect.objectContaining({ method: "GET" }),
    );
    expect(fetchMock).toHaveBeenCalledWith(
      "/evaluation/setups",
      expect.objectContaining({ method: "GET" }),
    );
    expect(fetchMock).toHaveBeenCalledWith(
      "/evaluation/calibration",
      expect.objectContaining({ method: "GET" }),
    );
    expect(fetchMock).toHaveBeenCalledWith(
      "/evaluation/benchmark",
      expect.objectContaining({ method: "GET" }),
    );
    expect(fetchMock).toHaveBeenCalledWith(
      "/evaluation/costs",
      expect.objectContaining({ method: "GET" }),
    );
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

  it("rejects blank control reasons before calling the API", async () => {
    const fetchMock = stubSystemFetch();
    window.history.pushState({}, "", "/app/controls");

    render(<App />);

    fireEvent.click(await screen.findByRole("button", { name: "Review SOFT_HALT" }));

    expect(screen.getByText("Reason is required before this operation can be reviewed.")).toBeInTheDocument();
    expect(hasPostCall(fetchMock, "/ops/halt")).toBe(false);
  });

  it("requires confirmation before submitting a resume request", async () => {
    const fetchMock = stubSystemFetch();
    window.history.pushState({}, "", "/app/controls");

    render(<App />);

    fireEvent.change(await screen.findByLabelText("Resume reason"), {
      target: {
        value: "operator checked safety state",
      },
    });
    fireEvent.click(screen.getByRole("button", { name: "Review resume request" }));

    expect(screen.getByText("Confirm before sending")).toBeInTheDocument();
    expect(
      within(screen.getByRole("group", { name: "Confirm resume request confirmation" })).getByText(
        "operator checked safety state",
      ),
    ).toBeInTheDocument();
    expect(hasPostCall(fetchMock, "/ops/resume")).toBe(false);

    fireEvent.click(screen.getByRole("button", { name: "Confirm resume request" }));

    expect(await screen.findByText("Resume requested")).toBeInTheDocument();
    expect(postBody(fetchMock, "/ops/resume")).toEqual({
      reason: "operator checked safety state",
    });
  });

  it("locks every mutating control while one operation is in flight", async () => {
    const fetchMock = stubSystemFetch({
      resumeResponse: new Promise<Response>(() => undefined),
    });
    window.history.pushState({}, "", "/app/controls");

    render(<App />);

    fireEvent.change(await screen.findByLabelText("Resume reason"), {
      target: {
        value: "operator checked safety state",
      },
    });
    fireEvent.click(screen.getByRole("button", { name: "Review resume request" }));
    fireEvent.change(screen.getByLabelText("HARD_HALT reason"), {
      target: {
        value: "emergency safety stop",
      },
    });
    fireEvent.click(screen.getByRole("button", { name: "Review HARD_HALT" }));

    const hardHaltConfirmButton = screen.getByRole("button", { name: "Confirm HARD_HALT" });

    fireEvent.click(screen.getByRole("button", { name: "Confirm resume request" }));

    await waitFor(() => {
      expect(hardHaltConfirmButton).toBeDisabled();
    });
    fireEvent.click(hardHaltConfirmButton);

    expect(postBody(fetchMock, "/ops/resume")).toEqual({
      reason: "operator checked safety state",
    });
    expect(hasPostCall(fetchMock, "/ops/halt")).toBe(false);
  });

  it("submits confirmed halt actions with the selected level", async () => {
    const fetchMock = stubSystemFetch();
    window.history.pushState({}, "", "/app/controls");

    render(<App />);

    fireEvent.change(await screen.findByLabelText("HARD_HALT reason"), {
      target: {
        value: "emergency safety stop",
      },
    });
    fireEvent.click(screen.getByRole("button", { name: "Review HARD_HALT" }));
    fireEvent.click(screen.getByRole("button", { name: "Confirm HARD_HALT" }));

    expect(await screen.findByText("HARD_HALT set")).toBeInTheDocument();
    expect(postBody(fetchMock, "/ops/halt")).toEqual({
      level: "HARD",
      reason: "emergency safety stop",
    });
  });

  it("shows manual trigger 409 refusal reasons in user-facing language", async () => {
    stubSystemFetch({
      triggerResponse: {
        status: 409,
        body: {
          message: "max_invocations_per_hour_exceeded",
        },
      },
    });
    window.history.pushState({}, "", "/app/controls");

    render(<App />);

    fireEvent.change(await screen.findByLabelText("Manual trigger reason"), {
      target: {
        value: "operator requested a smoke check",
      },
    });
    fireEvent.click(screen.getByRole("button", { name: "Review manual trigger" }));
    fireEvent.click(screen.getByRole("button", { name: "Confirm manual trigger" }));

    expect(await screen.findByText("Manual trigger failed")).toBeInTheDocument();
    expect(screen.getByText(/hourly LLM invocation cap has already been reached/)).toBeInTheDocument();
  });

  it("shows halt 409 refusal reasons in user-facing language", async () => {
    stubSystemFetch({
      haltResponse: {
        status: 409,
        body: {
          message: "SOFT_HALT cannot downgrade HARD_HALT.",
        },
      },
    });
    window.history.pushState({}, "", "/app/controls");

    render(<App />);

    fireEvent.change(await screen.findByLabelText("SOFT_HALT reason"), {
      target: {
        value: "pause new entries after hard halt review",
      },
    });
    fireEvent.click(screen.getByRole("button", { name: "Review SOFT_HALT" }));
    fireEvent.click(screen.getByRole("button", { name: "Confirm SOFT_HALT" }));

    expect(await screen.findByText("SOFT_HALT failed")).toBeInTheDocument();
    expect(screen.getByText(/HARD_HALT is already active/)).toBeInTheDocument();
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
  haltResponse?: {
    status: number;
    body: unknown;
  };
  resumeResponse?: Promise<Response>;
  triggerResponse?: {
    status: number;
    body: unknown;
  };
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

  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const path = requestPath(input);
    const method = requestMethod(input, init);

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
      case "/ops/halt": {
        if (method !== "POST") {
          return jsonResponse({ message: "method not allowed" }, { status: 405 });
        }

        const body = requestJson(init) as { level: "SOFT" | "HARD"; reason: string };
        const state = body.level === "HARD" ? "HARD_HALT" : "SOFT_HALT";

        if (fixture.haltResponse) {
          return jsonResponse(fixture.haltResponse.body, { status: fixture.haltResponse.status });
        }

        return jsonResponse({
          state,
          haltReason: body.reason,
          haltAt: "2026-07-05T12:05:00.000Z",
          resumedAt: null,
          resumedReason: null,
          drawdownRatio: "0.01",
        });
      }
      case "/ops/resume": {
        if (method !== "POST") {
          return jsonResponse({ message: "method not allowed" }, { status: 405 });
        }

        const body = requestJson(init) as { reason: string };

        if (fixture.resumeResponse) {
          return fixture.resumeResponse;
        }

        return jsonResponse({
          state: "RUNNING",
          haltReason: null,
          haltAt: null,
          resumedAt: "2026-07-05T12:06:00.000Z",
          resumedReason: body.reason,
          drawdownRatio: "0.01",
        });
      }
      case "/ops/trigger": {
        if (method !== "POST") {
          return jsonResponse({ message: "method not allowed" }, { status: 405 });
        }

        if (fixture.triggerResponse) {
          return jsonResponse(fixture.triggerResponse.body, { status: fixture.triggerResponse.status });
        }

        return jsonResponse(
          {
            invocationId: "manual-invocation-1",
            triggerKind: "MANUAL",
          },
          { status: 202 },
        );
      }
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
      case "/evaluation/setups":
        return jsonResponse({
          period: {
            from: "2026-06-05",
            to: "2026-07-05",
            timezone: "Asia/Tokyo",
          },
          truncated: false,
          setups: [
            {
              setupTag: "trend-breakout",
              performance: {
                tradeCount: 2,
                totalPnlJpy: "1800",
                profitFactor: "1.8",
                winRate: "0.5",
                expectedR: "0.3",
                averageMaeR: "0.12",
                averageMfeR: "0.4",
                rUnavailableCount: 0,
                maeUnavailableCount: 0,
                mfeUnavailableCount: 0,
              },
            },
            {
              setupTag: "range-fade",
              performance: {
                tradeCount: 1,
                totalPnlJpy: "-600",
                profitFactor: "0.8",
                winRate: "0",
                expectedR: "-0.1",
                averageMaeR: "0.2",
                averageMfeR: "0.1",
                rUnavailableCount: 0,
                maeUnavailableCount: 0,
                mfeUnavailableCount: 0,
              },
            },
          ],
          marketRegimes: [
            {
              trend: "TREND",
              volatility: "HIGH",
              performance: {
                tradeCount: 2,
                totalPnlJpy: "1800",
                profitFactor: "1.8",
                winRate: "0.5",
                expectedR: "0.3",
                averageMaeR: "0.12",
                averageMfeR: "0.4",
                rUnavailableCount: 0,
                maeUnavailableCount: 0,
                mfeUnavailableCount: 0,
              },
            },
          ],
        });
      case "/evaluation/calibration":
        return jsonResponse({
          period: {
            from: "2026-06-05",
            to: "2026-07-05",
            timezone: "Asia/Tokyo",
          },
          truncated: false,
          bySetup: [
            {
              groupKey: "trend-breakout",
              bins: [
                {
                  binIndex: 4,
                  lowerBoundInclusive: "0.4",
                  upperBoundInclusive: "0.5",
                  tradeCount: 1,
                  averageEstimatedProbability: "0.45",
                  realizedWinRate: "0",
                },
                {
                  binIndex: 6,
                  lowerBoundInclusive: "0.6",
                  upperBoundInclusive: "0.7",
                  tradeCount: 1,
                  averageEstimatedProbability: "0.64",
                  realizedWinRate: "1",
                },
              ],
            },
          ],
          byProvider: [
            {
              groupKey: "claude",
              bins: [
                {
                  binIndex: 5,
                  lowerBoundInclusive: "0.5",
                  upperBoundInclusive: "0.6",
                  tradeCount: 2,
                  averageEstimatedProbability: "0.55",
                  realizedWinRate: "0.5",
                },
              ],
            },
          ],
        });
      case "/evaluation/benchmark":
        return jsonResponse({
          period: {
            from: "2026-06-05",
            to: "2026-07-05",
            timezone: "Asia/Tokyo",
          },
          assumptionsJa: "buy & hold は初期残高で BTC を購入し、no-trade は現金維持として比較します。",
          baselineEquityJpy: "100000",
          points: [
            {
              date: "2026-06-29",
              buyAndHoldEquityJpy: "98000",
              noTradeEquityJpy: "100000",
              botEquityJpy: "98000",
            },
            {
              date: "2026-06-30",
              buyAndHoldEquityJpy: "99000",
              noTradeEquityJpy: "100000",
              botEquityJpy: "99000",
            },
            {
              date: "2026-07-01",
              buyAndHoldEquityJpy: "99500",
              noTradeEquityJpy: "100000",
              botEquityJpy: "100000",
            },
            {
              date: "2026-07-02",
              buyAndHoldEquityJpy: "100500",
              noTradeEquityJpy: "100000",
              botEquityJpy: "102000",
            },
            {
              date: "2026-07-03",
              buyAndHoldEquityJpy: "100000",
              noTradeEquityJpy: "100000",
              botEquityJpy: "100000",
            },
            {
              date: "2026-07-04",
              buyAndHoldEquityJpy: "103000",
              noTradeEquityJpy: "100000",
              botEquityJpy: "104000",
            },
            {
              date: "2026-07-05",
              buyAndHoldEquityJpy: "102000",
              noTradeEquityJpy: "100000",
              botEquityJpy: "101000",
            },
          ],
          returns: {
            buyAndHoldReturn: "0.02",
            noTradeReturn: "0",
            botReturn: "0.01",
          },
        });
      case "/evaluation/costs":
        return jsonResponse({
          period: {
            from: "2026-06-05",
            to: "2026-07-05",
            timezone: "Asia/Tokyo",
          },
          truncated: false,
          phaseCount: 4,
          missingUsagePhaseCount: 1,
          totalCostUsd: "0.1234",
          byProvider: [
            {
              provider: "claude",
              totalCostUsd: "0.1234",
              phaseCount: 4,
              missingUsagePhaseCount: 1,
            },
          ],
          byModel: [
            {
              model: "claude-sonnet-4",
              inputTokens: 1200,
              outputTokens: 450,
              cacheCreationInputTokens: 80,
              cacheReadInputTokens: 320,
            },
          ],
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

function requestMethod(input: RequestInfo | URL, init?: RequestInit): string {
  if (init?.method) {
    return init.method;
  }

  return input instanceof Request ? input.method : "GET";
}

function requestJson(init?: RequestInit): unknown {
  return typeof init?.body === "string" ? JSON.parse(init.body) : null;
}

type FetchMock = ReturnType<typeof vi.fn>;
type FetchCall = [RequestInfo | URL, RequestInit?];

function hasPostCall(fetchMock: FetchMock, path: string): boolean {
  return fetchMock.mock.calls.some((call) => isPostCall(call, path));
}

function postBody(fetchMock: FetchMock, path: string): unknown {
  const call = fetchMock.mock.calls.find((mockCall) => isPostCall(mockCall, path));

  return requestJson(toFetchCall(call ?? [])[1]);
}

function isPostCall(call: unknown[], path: string): boolean {
  const [input, init] = toFetchCall(call);

  return requestPath(input) === path && requestMethod(input, init) === "POST";
}

function toFetchCall(call: unknown[]): FetchCall {
  return [call[0] as RequestInfo | URL, call[1] as RequestInit | undefined];
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
