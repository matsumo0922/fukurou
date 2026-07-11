import { afterEach, describe, expect, it, vi } from "vitest";
import { generateReport, ReportAdmissionError } from "./evaluationReport";

const job = {
  jobId: "job-1", revisionId: "revision-1", revisionNumber: 7, status: "REQUESTED", stage: "ADMITTED",
  failureCode: null, failureMessage: null, activeInvocationId: null, retryAfterSeconds: null,
};

describe("evaluation report generation client", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("surfaces rejected job identity and Retry-After", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify({
      ...job, status: "REJECTED", stage: "REJECTED", failureCode: "CONCURRENT_INVOCATION",
      activeInvocationId: "active-1", retryAfterSeconds: 15,
    }), { status: 409, headers: { "Content-Type": "application/json", "Retry-After": "15" } })));
    const progress = vi.fn();

    const error = await generateReport({ kind: "PRESET", days: 30 }, new AbortController().signal, progress)
      .catch((cause: unknown) => cause);

    expect(error).toBeInstanceOf(ReportAdmissionError);
    expect((error as ReportAdmissionError).job.activeInvocationId).toBe("active-1");
    expect((error as ReportAdmissionError).retryAfter).toBe("15");
    expect(progress).toHaveBeenCalledOnce();
  });

  it("reports every stage and stops at terminal success", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify(job), { status: 202, headers: { "Content-Type": "application/json" } }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ ...job, status: "RUNNING", stage: "VALIDATING" }), { status: 200, headers: { "Content-Type": "application/json" } }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ ...job, status: "SUCCEEDED", stage: "COMPLETE" }), { status: 200, headers: { "Content-Type": "application/json" } }));
    vi.stubGlobal("fetch", fetchMock);
    const progress = vi.fn();

    await generateReport({ kind: "PRESET", days: 30 }, new AbortController().signal, progress);

    expect(progress.mock.calls.map(([value]) => value.stage)).toEqual(["ADMITTED", "VALIDATING", "COMPLETE"]);
  });
});
