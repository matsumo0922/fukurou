import { afterEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import App from "./App";
import { ACTIVITY_TIMELINE_FILTER_STORAGE_KEY } from "./api/ops";
import { LOCALE_STORAGE_KEY } from "./i18n/messages";
import { readActivityCatalogGolden } from "./test/activityCatalogGolden";
import { formatDateTime } from "./ui/format";

type ActivityCatalogGolden = {
  auditEventTypes: Array<{
    value: string;
  }>;
};

const activityCatalogGolden = readActivityCatalogGolden<ActivityCatalogGolden>();

describe("App", () => {
  afterEach(() => {
    cleanup();
    vi.useRealTimers();
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
    window.localStorage.clear();
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
    expect(screen.getByRole("link", { name: /Config/ })).toHaveAttribute("href", "/app/config");
    expect(screen.getByRole("link", { name: /Controls/ })).toHaveAttribute("href", "/app/controls");
    expect(screen.getByRole("link", { name: /Evaluation/ })).toHaveAttribute("href", "/app/evaluation");
    expect(screen.getByRole("link", { name: /System/ })).toHaveAttribute("href", "/app/system");
    expect(screen.getByText("🦉")).toBeInTheDocument();
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
    expect(screen.getByText("GET /ops/llm-auth")).toBeInTheDocument();
    expect(screen.getByText("status=ok")).toBeInTheDocument();
    expect(screen.getByText("status=ready")).toBeInTheDocument();
    expect(screen.getByText("claude=logged_in, codex=logged_out")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "CLI auth status" })).toBeInTheDocument();
    expect(screen.getByText("Claude Code")).toBeInTheDocument();
    expect(screen.getAllByText("local-sha").length).toBeGreaterThan(0);
    expect(screen.getAllByText(/JST/).length).toBeGreaterThan(1);
  });

  it("shows editable runtime config without exposing secret values", async () => {
    const fetchMock = stubSystemFetch();
    window.history.pushState({}, "", "/app/config");

    render(<App />);

    expect(await screen.findByRole("heading", { name: "Config" })).toBeInTheDocument();
    expect(screen.getByText("Runtime config draft, validation, activation, rollback, deployment boundaries, and secret status.")).toBeInTheDocument();
    expect(await screen.findByRole("heading", { name: "Runtime" }, { timeout: 5_000 })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Deployment" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Secrets" })).toBeInTheDocument();
    expect(screen.getByText("GMO public base URL")).toBeInTheDocument();
    expect(screen.getAllByText("FUKUROU_GMO_PUBLIC_BASE_URL").length).toBeGreaterThan(0);
    expect(screen.getAllByText("https://api.coin.z.com/public").length).toBeGreaterThan(0);
    expect(screen.getAllByText("configured").length).toBeGreaterThan(0);
    expect(screen.queryByText("super-secret-password")).not.toBeInTheDocument();
    expect(hasGetCall(fetchMock, "/ops/runtime-config", () => true)).toBe(true);
  });

  it("shows runtime config warnings and validation errors", async () => {
    stubSystemFetch({
      runtimeConfigResponse: {
        ...runtimeConfigResponse(),
        versions: [],
        warnings: [
          {
            code: "runtimeConfig.warning.activeValidationFailed",
            validation: {
              valid: false,
              errors: [
                {
                  code: "runtimeConfig.validation.missingKeys",
                  params: {
                    keys: "runner.maxToolCallsPerRun",
                  },
                },
              ],
            },
          },
        ],
      },
    });
    window.history.pushState({}, "", "/app/config");

    render(<App />);

    expect(await screen.findByRole("heading", { name: "Config" })).toBeInTheDocument();
    expect(await screen.findByRole("heading", { name: "Runtime config warnings" })).toBeInTheDocument();
    expect(
      screen.getByText(
        "Active runtime config is invalid. Trading workers and manual trigger are halted until a valid version is activated or rolled back.",
      ),
    ).toBeInTheDocument();
    expect(screen.getByText("Missing keys: runner.maxToolCallsPerRun.")).toBeInTheDocument();
    expect(screen.getByText("No records")).toBeInTheDocument();
  });

  it("shows runtime config rollback validation errors", async () => {
    const fetchMock = stubSystemFetch({
      runtimeConfigResponse: runtimeConfigResponseWithInactiveVersion(),
      runtimeConfigRollbackResponse: {
        status: 409,
        body: {
          valid: false,
          errors: [
            {
              code: "runtimeConfig.validation.typedBetweenInclusive",
              key: "runner.maxToolCallsPerRun",
              params: {
                min: "1",
                max: "48",
              },
            },
          ],
        },
      },
    });
    window.history.pushState({}, "", "/app/config");

    render(<App />);

    expect(await screen.findByRole("heading", { name: "Config" })).toBeInTheDocument();

    fireEvent.click(await screen.findByRole("button", { name: "Rollback" }));

    expect(await screen.findByText("rollback rejected")).toBeInTheDocument();
    expect(screen.getByText("runner.maxToolCallsPerRun must be between 1 and 48.")).toBeInTheDocument();
    expect(hasPostCall(fetchMock, "/ops/runtime-config/versions/runtime-config-previous/rollback")).toBe(true);
  });

  it("starts in English and switches supported UI copy to Japanese", async () => {
    stubSystemFetch();
    window.history.pushState({}, "", "/app/overview");

    render(<App />);

    expect(await screen.findByRole("heading", { name: "Overview" })).toBeInTheDocument();
    expect(screen.getByText("Safety, risk, freshness, and paper account state.")).toBeInTheDocument();
    expect(document.documentElement).toHaveAttribute("lang", "en");

    fireEvent.click(screen.getByRole("button", { name: "JA" }));

    expect(screen.getByText("安全性、リスク、鮮度、paper 口座の状態。")).toBeInTheDocument();
    expect(screen.getAllByRole("button", { name: "更新" }).length).toBeGreaterThan(0);
    expect(window.localStorage.getItem(LOCALE_STORAGE_KEY)).toBe("ja");
    expect(document.documentElement).toHaveAttribute("lang", "ja");
  });

  it("persists Japanese locale while keeping major titles and operational values unchanged", async () => {
    window.localStorage.setItem(LOCALE_STORAGE_KEY, "ja");
    stubSystemFetch();
    window.history.pushState({}, "", "/app/overview");

    render(<App />);

    expect(await screen.findByRole("heading", { name: "Overview" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /Overview/ })).toHaveAttribute("href", "/app/overview");
    expect(screen.getByRole("link", { name: /Activity/ })).toHaveAttribute("href", "/app/activity");
    expect(screen.getByRole("link", { name: /System/ })).toHaveAttribute("href", "/app/system");
    expect(screen.getByText("安全性、リスク、鮮度、paper 口座の状態。")).toBeInTheDocument();
    await waitFor(() => {
      expect(screen.getAllByText("RUNNING").length).toBeGreaterThan(0);
      expect(screen.getAllByText("NO_TRADE").length).toBeGreaterThan(0);
      expect(screen.getAllByText("PAPER").length).toBeGreaterThan(0);
    });
  });

  it("localizes evaluation UI copy while keeping operational values unchanged", async () => {
    window.localStorage.setItem(LOCALE_STORAGE_KEY, "ja");
    stubSystemFetch();
    window.history.pushState({}, "", "/app/evaluation");

    render(<App />);

    expect(await screen.findByRole("heading", { name: "Evaluation" })).toBeInTheDocument();
    expect(screen.getByText("モデル品質、paper trading 成績、キャリブレーション、ベンチマーク、停止判定、LLM コスト。")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "セットアップ成績" })).toBeInTheDocument();
    expect(await screen.findByText("Bot 実績")).toBeInTheDocument();
    expect(screen.getAllByText("NO_TRADE").length).toBeGreaterThan(0);
    expect(screen.getByText("claude-sonnet-4")).toBeInTheDocument();
  });

  it("localizes controls UI copy while keeping safety states unchanged", async () => {
    window.localStorage.setItem(LOCALE_STORAGE_KEY, "ja");
    stubSystemFetch();
    window.history.pushState({}, "", "/app/controls");

    render(<App />);

    expect(await screen.findByRole("heading", { name: "Controls" })).toBeInTheDocument();
    expect(screen.getByText("停止、再開、手動 one-shot LLM 起動のための理由付き運用操作。")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "停止操作" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "SOFT_HALT を確認" })).toBeInTheDocument();
    expect(screen.getByLabelText("再開理由")).toBeInTheDocument();
    expect(screen.getAllByText("RUNNING").length).toBeGreaterThan(0);
  });

  it("keeps the selected locale usable when browser storage writes fail", async () => {
    const storageSetItemSpy = vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new Error("storage disabled");
    });

    stubSystemFetch();
    window.history.pushState({}, "", "/app/overview");

    render(<App />);

    expect(await screen.findByRole("heading", { name: "Overview" })).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "JA" }));

    expect(screen.getByText("安全性、リスク、鮮度、paper 口座の状態。")).toBeInTheDocument();
    expect(document.documentElement).toHaveAttribute("lang", "ja");
    expect(storageSetItemSpy).toHaveBeenCalled();
  });

  it("formats Japanese date-times with a ja-JP leaning and the JST label", () => {
    expect(formatDateTime("2026-07-05T12:01:00.000Z", "ja")).toBe("2026/07/05 21:01:00 JST");
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

  it("shows activity timeline filters and loads older cursor pages", async () => {
    const fetchMock = stubSystemFetch();
    window.history.pushState({}, "", "/app/activity");

    render(<App />);

    expect(await screen.findByText("BTC entry fill")).toBeInTheDocument();
    expect(screen.getAllByText("No trade").length).toBeGreaterThan(0);
    expect(screen.getAllByText("Manual resume requested").length).toBeGreaterThan(0);
    expect(screen.getAllByText("operator").length).toBeGreaterThan(0);
    expect(screen.getByText(/newest first/)).toBeInTheDocument();
    expect(screen.getByText(/3\/50 records/)).toBeInTheDocument();

    const timeline = await screen.findByRole("list", { name: "Activity timeline" });
    const timelineItems = within(timeline).getAllByRole("listitem");

    expect(timelineItems.map((item) => within(item).getByRole("heading", { level: 2 }).textContent)).toEqual([
      "BTC entry fill",
      "Manual resume requested",
      "No trade",
    ]);
    expect(within(timeline).queryByText("Entry reason stays in the detail dialog.")).not.toBeInTheDocument();

    fireEvent.click(
      within(timelineItems[0]).getByRole("button", {
        name: "Open execution details for BTC entry fill",
      }),
    );

    const detailsDialog = screen.getByRole("dialog", { name: "BTC entry fill" });

    expect(within(detailsDialog).getByText("Entry reason stays in the detail dialog.")).toBeInTheDocument();
    expect(within(detailsDialog).getByText("order-1")).toBeInTheDocument();

    fireEvent.keyDown(window, { key: "Escape" });

    await waitFor(() => {
      expect(screen.queryByRole("dialog", { name: "BTC entry fill" })).not.toBeInTheDocument();
    });
    expect(hasGetCall(fetchMock, "/ops/activity", (params) => params.get("limit") === "50")).toBe(true);

    fireEvent.click(screen.getByRole("button", { name: "Load older" }));

    expect(await screen.findByText("HARD_HALT set")).toBeInTheDocument();
    expect(
      hasGetCall(
        fetchMock,
        "/ops/activity",
        (params) => params.get("before") === "2026-07-05T12:02:00.000Z|decision|decision:decision-1",
      ),
    ).toBe(true);

    fireEvent.click(screen.getByRole("button", { name: "Audit" }));
    fireEvent.click(screen.getByLabelText("Manual resume requested"));

    await waitFor(() => {
      expect(
        hasGetCall(
          fetchMock,
          "/ops/activity",
          (params) => params.get("source") === "audit" && params.get("auditEventType") === "MANUAL_RESUME_REQUESTED",
        ),
      ).toBe(true);
    });
    expect(JSON.parse(window.localStorage.getItem(ACTIVITY_TIMELINE_FILTER_STORAGE_KEY) ?? "{}")).toEqual({
      source: "audit",
      auditEventTypes: ["MANUAL_RESUME_REQUESTED"],
    });
  });

  it("restores valid activity filters and drops stale saved values", async () => {
    window.localStorage.setItem(
      ACTIVITY_TIMELINE_FILTER_STORAGE_KEY,
      JSON.stringify({
        source: "audit",
        auditEventTypes: ["HARD_HALT_SET", "STALE_EVENT"],
      }),
    );
    const fetchMock = stubSystemFetch();
    window.history.pushState({}, "", "/app/activity");

    render(<App />);

    expect(await screen.findByRole("heading", { name: "Activity" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Audit" })).toHaveAttribute("aria-pressed", "true");
    expect(await screen.findByLabelText("HARD_HALT set")).toBeChecked();
    expect(screen.queryByText("STALE_EVENT")).not.toBeInTheDocument();

    await waitFor(() => {
      expect(
        hasGetCall(
          fetchMock,
          "/ops/activity",
          (params) => params.get("source") === "audit" && params.get("auditEventType") === "HARD_HALT_SET",
        ),
      ).toBe(true);
    });
    expect(
      hasGetCall(
        fetchMock,
        "/ops/activity",
        (params) => params.getAll("auditEventType").includes("STALE_EVENT"),
      ),
    ).toBe(false);
    await waitFor(() => {
      expect(JSON.parse(window.localStorage.getItem(ACTIVITY_TIMELINE_FILTER_STORAGE_KEY) ?? "{}")).toEqual({
        source: "audit",
        auditEventTypes: ["HARD_HALT_SET"],
      });
    });
  });

  it("opens the activity glossary and explains default audit exclusions", async () => {
    stubSystemFetch();
    window.history.pushState({}, "", "/app/activity");

    render(<App />);

    fireEvent.click(await screen.findByRole("button", { name: "Open activity glossary" }));

    const dialog = screen.getByRole("dialog", { name: "Activity glossary" });

    expect(within(dialog).getByText("Sources")).toBeInTheDocument();
    expect(within(dialog).getByText("Audit event types")).toBeInTheDocument();
    expect(within(dialog).getByText("Decision actions")).toBeInTheDocument();
    expect(within(dialog).getByText("Decision lifecycle completed")).toBeInTheDocument();
    expect(within(dialog).getByText(/RECONCILER_PASS_COMPLETED.*excluded by default/)).toBeInTheDocument();

    fireEvent.keyDown(window, { key: "Escape" });

    await waitFor(() => {
      expect(screen.queryByRole("dialog", { name: "Activity glossary" })).not.toBeInTheDocument();
    });
  });

  it("keeps saved filters and falls back to raw labels when the activity catalog fails", async () => {
    window.localStorage.setItem(
      ACTIVITY_TIMELINE_FILTER_STORAGE_KEY,
      JSON.stringify({
        source: "all",
        auditEventTypes: ["STALE_EVENT"],
      }),
    );
    const fetchMock = stubSystemFetch({
      activityCatalogResponse: {
        status: 500,
        body: { message: "catalog unavailable" },
      },
    });
    window.history.pushState({}, "", "/app/activity");

    render(<App />);

    expect(await screen.findByText("NO_TRADE decision")).toBeInTheDocument();
    expect(screen.getByLabelText("STALE_EVENT")).toBeChecked();
    expect(
      hasGetCall(
        fetchMock,
        "/ops/activity",
        (params) => params.getAll("auditEventType").includes("STALE_EVENT"),
      ),
    ).toBe(false);
    expect(JSON.parse(window.localStorage.getItem(ACTIVITY_TIMELINE_FILTER_STORAGE_KEY) ?? "{}")).toEqual({
      source: "all",
      auditEventTypes: ["STALE_EVENT"],
    });
  });

  it("ignores an older activity page response after filters change", async () => {
    let resolveOlderResponse: (response: Response) => void = () => undefined;
    const activityOlderResponse = new Promise<Response>((resolve) => {
      resolveOlderResponse = resolve;
    });
    const fetchMock = stubSystemFetch({
      activityOlderResponse,
    });
    window.history.pushState({}, "", "/app/activity");

    render(<App />);

    expect(await screen.findByText("BTC entry fill")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Load older" }));
    fireEvent.click(screen.getByRole("button", { name: "Audit" }));

    await waitFor(() => {
      expect(hasGetCall(fetchMock, "/ops/activity", (params) => params.get("source") === "audit")).toBe(true);
    });

    await act(async () => {
      resolveOlderResponse(jsonResponse(activityTimelineOlderPage()));
      await activityOlderResponse;
      await Promise.resolve();
    });

    const timeline = await screen.findByRole("list", { name: "Activity timeline" });

    expect(within(timeline).queryByText("HARD_HALT set")).not.toBeInTheDocument();
    expect(within(timeline).getAllByText("Manual resume requested").length).toBeGreaterThan(0);
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

  it("starts confirmed CLI auth login and displays the authorization challenge", async () => {
    const fetchMock = stubSystemFetch();
    window.history.pushState({}, "", "/app/controls");

    render(<App />);

    fireEvent.change(await screen.findByLabelText("Codex login reason"), {
      target: {
        value: "operator requested Codex re-auth",
      },
    });
    fireEvent.click(screen.getByRole("button", { name: "Review Codex login" }));
    fireEvent.click(screen.getByRole("button", { name: "Confirm Codex login" }));

    expect(await screen.findByText("CLI auth login started")).toBeInTheDocument();
    expect(screen.getByText("ABCD-EFGH")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "https://auth.example.com/device" })).toHaveAttribute(
      "href",
      "https://auth.example.com/device",
    );
    expect(postBody(fetchMock, "/ops/llm-auth/codex/login")).toEqual({
      reason: "operator requested Codex re-auth",
    });
    expect(screen.queryByLabelText("token/code")).not.toBeInTheDocument();
  });

  it("submits a Claude login token code without echoing it", async () => {
    const fetchMock = stubSystemFetch({
      llmAuthLoginSession: defaultLlmAuthLoginSession("claude"),
    });
    window.history.pushState({}, "", "/app/controls");

    render(<App />);

    fireEvent.change(await screen.findByLabelText("Claude Code login reason"), {
      target: {
        value: "operator requested Claude re-auth",
      },
    });
    fireEvent.click(screen.getByRole("button", { name: "Review Claude Code login" }));
    fireEvent.click(screen.getByRole("button", { name: "Confirm Claude Code login" }));

    expect(await screen.findByText("CLI auth login started")).toBeInTheDocument();

    const tokenCodeInput = screen.getByLabelText("token/code");

    fireEvent.change(tokenCodeInput, {
      target: {
        value: "DUMMY-CODE",
      },
    });
    fireEvent.click(screen.getByRole("button", { name: "Submit token/code" }));

    expect(await screen.findByText("Claude auth token/code submitted")).toBeInTheDocument();
    expect(postBody(fetchMock, "/ops/llm-auth/claude/login/session-1/token")).toEqual({
      code: "DUMMY-CODE",
    });
    expect(screen.queryByDisplayValue("DUMMY-CODE")).not.toBeInTheDocument();
    expect(screen.getByText("Token/code submitted. Waiting for CLI completion.")).toBeInTheDocument();
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
  llmAuth?: {
    providers: Array<{
      provider: string;
      displayName: string;
      status: string;
      detail?: string | null;
      homePath: string;
      checkedAt: string;
    }>;
    checkedAt: string;
  };
  llmAuthLoginResponse?: {
    status: number;
    body: unknown;
  };
  llmAuthLoginSession?: ReturnType<typeof defaultLlmAuthLoginSession>;
  llmAuthTokenSubmitResponse?: {
    status: number;
    body: unknown;
  };
  haltResponse?: {
    status: number;
    body: unknown;
  };
  resumeResponse?: Promise<Response>;
  triggerResponse?: {
    status: number;
    body: unknown;
  };
  runtimeConfigResponse?: unknown;
  runtimeConfigRollbackResponse?: {
    status: number;
    body: unknown;
  };
  activityCatalogResponse?: {
    status: number;
    body: unknown;
  };
  activityOlderResponse?: Promise<Response>;
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
  const llmAuth = fixture.llmAuth ?? defaultLlmAuthResponse();
  const revision = fixture.revision ?? "test-sha";
  const readinessStatus = fixture.readinessStatus ?? 200;

  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const path = requestPath(input);
    const method = requestMethod(input, init);

    if (path.startsWith("/ops/llm-auth/") && path.endsWith("/token")) {
      if (method !== "POST") {
        return jsonResponse({ message: "method not allowed" }, { status: 405 });
      }

      if (fixture.llmAuthTokenSubmitResponse) {
        return jsonResponse(
          fixture.llmAuthTokenSubmitResponse.body,
          { status: fixture.llmAuthTokenSubmitResponse.status },
        );
      }

      return jsonResponse(defaultLlmAuthTokenSubmitResponse(), { status: 202 });
    }

    if (path.startsWith("/ops/llm-auth/") && path.endsWith("/login")) {
      if (method !== "POST") {
        return jsonResponse({ message: "method not allowed" }, { status: 405 });
      }

      if (fixture.llmAuthLoginResponse) {
        return jsonResponse(fixture.llmAuthLoginResponse.body, { status: fixture.llmAuthLoginResponse.status });
      }

      return jsonResponse(fixture.llmAuthLoginSession ?? defaultLlmAuthLoginSession(providerFromLlmAuthPath(path)), { status: 202 });
    }

    if (path.startsWith("/ops/llm-auth/") && path.includes("/login/")) {
      return jsonResponse(fixture.llmAuthLoginSession ?? defaultLlmAuthLoginSession(providerFromLlmAuthPath(path)));
    }

    if (path.startsWith("/ops/runtime-config/versions/") && path.endsWith("/rollback")) {
      if (method !== "POST") {
        return jsonResponse({ message: "method not allowed" }, { status: 405 });
      }

      if (fixture.runtimeConfigRollbackResponse) {
        return jsonResponse(
          fixture.runtimeConfigRollbackResponse.body,
          { status: fixture.runtimeConfigRollbackResponse.status },
        );
      }

      return jsonResponse({
        activeVersion: runtimeConfigResponse().activeVersion,
        previousActiveVersionId: "runtime-config-active",
        validation: { valid: true, errors: [] },
      });
    }

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
      case "/ops/llm-auth":
        return jsonResponse(llmAuth);
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
      case "/ops/runtime-config":
        return jsonResponse(fixture.runtimeConfigResponse ?? runtimeConfigResponse());
      case "/ops/activity/catalog":
        if (fixture.activityCatalogResponse) {
          return jsonResponse(
            fixture.activityCatalogResponse.body,
            { status: fixture.activityCatalogResponse.status },
          );
        }

        return jsonResponse(activityCatalogGolden);
      case "/ops/activity":
        {
          const searchParams = requestSearchParams(input);

          if (unknownAuditEventTypes(searchParams).length > 0) {
            return jsonResponse({ message: "Unknown auditEventType" }, { status: 400 });
          }

          if (searchParams.has("before") && fixture.activityOlderResponse) {
            return fixture.activityOlderResponse;
          }

          return jsonResponse(activityTimelineResponse(searchParams));
        }
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
          sellExecutions: [],
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

function defaultLlmAuthResponse() {
  return {
    providers: [
      {
        provider: "claude",
        displayName: "Claude Code",
        status: "logged_in",
        detail: "credential marker present",
        homePath: "/tmp/fukurou-cli-home/.claude",
        checkedAt: "2026-07-05T12:00:00.000Z",
      },
      {
        provider: "codex",
        displayName: "Codex",
        status: "logged_out",
        detail: "credential marker not found",
        homePath: "/tmp/fukurou-cli-home/.codex",
        checkedAt: "2026-07-05T12:00:00.000Z",
      },
    ],
    checkedAt: "2026-07-05T12:00:00.000Z",
  };
}

function defaultLlmAuthLoginSession(provider = "codex") {
  return {
    provider,
    sessionId: "session-1",
    status: "running",
    authorizationUrl: "https://auth.example.com/device",
    userCode: provider === "codex" ? "ABCD-EFGH" : null,
    tokenSubmitAvailable: provider === "claude",
    tokenSubmitted: false,
    detail: "authorization challenge emitted",
    startedAt: "2026-07-05T12:00:00.000Z",
    expiresAt: "2026-07-05T12:10:00.000Z",
    completedAt: null,
  };
}

function defaultLlmAuthTokenSubmitResponse() {
  return {
    provider: "claude",
    sessionId: "session-1",
    status: "running",
    tokenSubmitted: true,
    detail: "authorization token/code submitted; waiting for CLI completion",
  };
}

function providerFromLlmAuthPath(path: string): string {
  return path.includes("/claude/") ? "claude" : "codex";
}

function runtimeConfigResponse() {
  const activeVersion = {
    id: "runtime-config-active",
    status: "ACTIVE",
    createdAt: "2026-07-05T12:00:00.000Z",
    activatedAt: "2026-07-05T12:00:00.000Z",
    createdBy: "bootstrap",
    note: "code catalog defaults",
    hash: "abcdef1234567890",
  };

  return {
    activeVersion,
    versions: [activeVersion],
    groups: [
      {
        id: "runtime",
        labelKey: "config.group.runtime.label",
        descriptionKey: "config.group.runtime.description",
        items: [
          runtimeConfigItem({
            key: "runner.maxToolCallsPerRun",
            legacyEnvName: "FUKUROU_MCP_TOTAL_TOOL_CALL_LIMIT",
            valueType: "INT",
            defaultValue: "48",
            effectiveValue: "48",
            unit: "calls",
            sourceKind: "RUNTIME",
          }),
        ],
      },
      {
        id: "deployment",
        labelKey: "config.group.deployment.label",
        descriptionKey: "config.group.deployment.description",
        items: [
          runtimeConfigItem({
            key: "gmoPublic.baseUrl",
            legacyEnvName: "FUKUROU_GMO_PUBLIC_BASE_URL",
            valueType: "URL",
            defaultValue: "https://api.coin.z.com/public",
            currentValue: "https://api.coin.z.com/public",
            effectiveValue: "https://api.coin.z.com/public",
            sourceKind: "DEPLOYMENT",
            safetyTier: "DEPLOYMENT_BOUNDARY",
          }),
          runtimeConfigItem({
            key: "llm.claudeCommandTemplate",
            legacyEnvName: "FUKUROU_CLAUDE_COMMAND_TEMPLATE",
            valueType: "STRING_LIST",
            defaultValue: "claude",
            effectiveValue: "claude",
            sourceKind: "DEPLOYMENT",
            safetyTier: "DEPLOYMENT_BOUNDARY",
          }),
        ],
      },
      {
        id: "secrets",
        labelKey: "config.group.secrets.label",
        descriptionKey: "config.group.secrets.description",
        items: [
          runtimeConfigItem({
            key: "database.password",
            legacyEnvName: "DB_PASSWORD",
            valueType: "SECRET_STATUS",
            defaultValue: null,
            currentValue: null,
            effectiveValue: null,
            sourceKind: "SECRET",
            valueConfigured: true,
            safetyTier: "SECRET",
          }),
        ],
      },
    ],
  };
}

function runtimeConfigResponseWithInactiveVersion() {
  const response = runtimeConfigResponse();
  const inactiveVersion = {
    id: "runtime-config-previous",
    status: "INACTIVE",
    createdAt: "2026-07-05T11:00:00.000Z",
    activatedAt: "2026-07-05T11:00:00.000Z",
    createdBy: "webui",
    note: "previous runtime config",
    hash: "fedcba0987654321",
  };

  return {
    ...response,
    versions: [response.activeVersion, inactiveVersion],
  };
}

function runtimeConfigItem({
  key,
  legacyEnvName,
  valueType,
  defaultValue,
  currentValue = null,
  effectiveValue,
  unit = null,
  sourceKind,
  valueConfigured = true,
  safetyTier = "STANDARD",
  editable = sourceKind === "RUNTIME",
  applyMode = sourceKind === "RUNTIME" ? "NEXT_RESTART" : "PROCESS_RESTART",
}: {
  key: string;
  legacyEnvName: string;
  valueType: string;
  defaultValue: string | null;
  currentValue?: string | null;
  effectiveValue: string | null;
  unit?: string | null;
  sourceKind: string;
  valueConfigured?: boolean;
  safetyTier?: string;
  editable?: boolean;
  applyMode?: string;
}) {
  return {
    key,
    sourceKind,
    valueType,
    defaultValue,
    currentValue,
    effectiveValue,
    unit,
    valueConfigured,
    legacyEnvName,
    editable,
    applyMode,
    safetyTier,
    labelKey: `config.item.${key}.label`,
    descriptionKey: `config.item.${key}.description`,
  };
}

function activityTimelineResponse(searchParams: URLSearchParams) {
  const source = searchParams.get("source");
  const auditEventTypes = searchParams.getAll("auditEventType");
  const before = searchParams.get("before");
  const baseEvents = before ? olderActivityEvents() : latestActivityEvents(source, auditEventTypes);
  const events = baseEvents
    .filter((event) => {
      const sourceMatched = source === null || event.source === source;
      const auditEventTypeMatched =
        auditEventTypes.length === 0 || event.source !== "audit" || auditEventTypes.includes(event.kind);

      return sourceMatched && auditEventTypeMatched;
    })
    .sort((firstEvent, secondEvent) => Date.parse(secondEvent.occurredAt) - Date.parse(firstEvent.occurredAt));

  return {
    events,
    nextBefore: before ? null : "2026-07-05T12:02:00.000Z|decision|decision:decision-1",
    limit: 50,
  };
}

function unknownAuditEventTypes(searchParams: URLSearchParams): string[] {
  const knownAuditEventTypes = new Set(activityCatalogGolden.auditEventTypes.map((item) => item.value));

  return searchParams.getAll("auditEventType").filter((eventType) => !knownAuditEventTypes.has(eventType));
}

function activityTimelineOlderPage() {
  return {
    events: olderActivityEvents(),
    nextBefore: null,
    limit: 50,
  };
}

function latestActivityEvents(source: string | null, auditEventTypes: string[]) {
  const filteredAuditRequested = source === "audit" && auditEventTypes.length > 0;

  if (filteredAuditRequested) {
    return [...defaultLatestActivityEvents(), ...olderActivityEvents()];
  }

  return defaultLatestActivityEvents();
}

function defaultLatestActivityEvents() {
  return [
    {
      id: "execution:execution-1",
      source: "execution",
      kind: "ENTRY_FILL",
      title: "BTC entry fill",
      detail: "0.01000000 BTC at 10000000 JPY",
      occurredAt: "2026-07-05T12:04:00.000Z",
      metadata: [
        {
          label: "realized pnl",
          value: "0",
        },
        {
          label: "fee",
          value: "10",
        },
        {
          label: "liquidity",
          value: "TAKER",
        },
        {
          label: "order",
          value: "order-1",
        },
      ],
      details: {
        title: "BTC entry fill",
        metadata: [
          {
            label: "execution",
            value: "execution-1",
          },
          {
            label: "side",
            value: "BUY",
          },
          {
            label: "size",
            value: "0.01000000",
          },
          {
            label: "price",
            value: "10000000",
          },
          {
            label: "realized pnl",
            value: "0",
          },
          {
            label: "fee",
            value: "10",
          },
          {
            label: "liquidity",
            value: "TAKER",
          },
          {
            label: "order",
            value: "order-1",
          },
          {
            label: "order type",
            value: "MARKET",
          },
          {
            label: "trigger price",
            value: "none",
          },
          {
            label: "take-profit price",
            value: "10200000",
          },
          {
            label: "order reason",
            value: "Entry order reason stays in the detail dialog.",
          },
          {
            label: "position",
            value: "position-1",
          },
          {
            label: "trade group",
            value: "trade-group-1",
          },
          {
            label: "decision action",
            value: "ENTER",
          },
          {
            label: "decision",
            value: "decision-entry-1",
          },
          {
            label: "decision run",
            value: "entry-run-1",
          },
          {
            label: "decision reason",
            value: "Entry reason stays in the detail dialog.",
          },
        ],
      },
    },
    {
      id: "audit:event-1",
      source: "audit",
      kind: "MANUAL_RESUME_REQUESTED",
      title: "MANUAL_RESUME_REQUESTED",
      detail: "operator",
      occurredAt: "2026-07-05T12:03:00.000Z",
      metadata: [
        {
          label: "tool",
          value: "operator",
        },
      ],
    },
    {
      id: "decision:decision-1",
      source: "decision",
      kind: "NO_TRADE",
      title: "NO_TRADE decision",
      detail: "条件が揃うまで待機します。",
      occurredAt: "2026-07-05T12:02:00.000Z",
      metadata: [
        {
          label: "estimated p",
          value: "0.42",
        },
        {
          label: "setup tags",
          value: "range",
        },
        {
          label: "no-trade conditions",
          value: "ボラティリティ不足",
        },
      ],
    },
  ];
}

function olderActivityEvents() {
  return [
    {
      id: "audit:event-older",
      source: "audit",
      kind: "HARD_HALT_SET",
      title: "HARD_HALT_SET",
      detail: "risk",
      occurredAt: "2026-07-05T12:01:00.000Z",
      metadata: [
        {
          label: "tool",
          value: "risk",
        },
      ],
    },
  ];
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

function requestSearchParams(input: RequestInfo | URL): URLSearchParams {
  if (input instanceof Request) {
    return new URL(input.url).searchParams;
  }

  if (input instanceof URL) {
    return input.searchParams;
  }

  return new URL(input, "http://localhost").searchParams;
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

function hasGetCall(
  fetchMock: FetchMock,
  path: string,
  matched: (searchParams: URLSearchParams) => boolean,
): boolean {
  return fetchMock.mock.calls.some((call) => {
    const [input, init] = toFetchCall(call);
    const pathMatched = requestPath(input) === path;
    const methodMatched = requestMethod(input, init) === "GET";

    return pathMatched && methodMatched && matched(requestSearchParams(input));
  });
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
