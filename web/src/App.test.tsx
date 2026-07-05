import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import App from "./App";

describe("App", () => {
  afterEach(() => {
    cleanup();
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

  vi.stubGlobal(
    "fetch",
    vi.fn(async (input: RequestInfo | URL) => {
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
        default:
          return jsonResponse({ message: "not found" }, { status: 404 });
      }
    }),
  );
}

function requestPath(input: RequestInfo | URL): string {
  if (input instanceof Request) {
    return new URL(input.url).pathname;
  }

  if (input instanceof URL) {
    return input.pathname;
  }

  return input;
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
