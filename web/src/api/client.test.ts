import { afterEach, describe, expect, it, vi } from "vitest";
import { fetchRevision, getText } from "./client";

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
});
