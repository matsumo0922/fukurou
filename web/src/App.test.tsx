import { afterEach, describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import App from "./App";

describe("App", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("shows revision after the placeholder connectivity check succeeds", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response("local-sha")));

    render(<App />);

    expect(screen.getByText("Checking API")).toBeInTheDocument();
    expect(await screen.findByText("API connected")).toBeInTheDocument();
    expect(screen.getByText(/revision local-sha/)).toBeInTheDocument();
  });
});
