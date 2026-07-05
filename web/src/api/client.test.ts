import { afterEach, describe, expect, it, vi } from "vitest";
import { fetchRevision, getJsonResponse, getText } from "./client";

describe("api client", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("fetches revision as typed plain text", async () => {
    const fetchMock = vi.fn(async () => new Response("abc123"));

    vi.stubGlobal("fetch", fetchMock);

    await expect(fetchRevision()).resolves.toBe("abc123");
    expect(fetchMock).toHaveBeenCalledWith(
      "/revision",
      expect.objectContaining({
        method: "GET",
      }),
    );
  });

  it("throws typed client error on failed response", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response("nope", { status: 503 })));

    await expect(getText("/revision")).rejects.toMatchObject({
      path: "/revision",
      status: 503,
      responseText: "nope",
    });
  });

  it("returns allowed non-200 JSON responses with their status", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(
        async () =>
          new Response(JSON.stringify({ status: "not_ready" }), {
            status: 503,
            headers: {
              "Content-Type": "application/json",
            },
          }),
      ),
    );

    await expect(getJsonResponse("/health/ready", [200, 503])).resolves.toEqual({
      status: 503,
      data: {
        status: "not_ready",
      },
    });
  });
});
