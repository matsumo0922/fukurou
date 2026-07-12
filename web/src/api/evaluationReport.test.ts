import { afterEach, describe, expect, it, vi } from "vitest";
import { generateReport, parseReportScopeKey, reportEffectivePeriodLabel, reportRevisionMatchesScope, ReportAdmissionError } from "./evaluationReport";

const job = {
  jobId: "job-1", revisionId: "revision-1", revisionNumber: 7, status: "REQUESTED", stage: "ADMITTED",
  failureCode: null, failureMessage: null, activeInvocationId: null, retryAfterSeconds: null,
  epochId: "epoch-1", cohort: "CURRENT",
};

describe("evaluation report generation client", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("decodes legacy and versioned report scope identities canonically", () => {
    expect(parseReportScopeKey("PRESET:30D")).toEqual({ base: "PRESET:30D", epochId: null, cohort: null });
    expect(parseReportScopeKey("PRESET:30D|EPOCH:epoch-1|COHORT:CURRENT")).toEqual({ base: "PRESET:30D", epochId: "epoch-1", cohort: "CURRENT" });
    expect(parseReportScopeKey("PRESET:30D|EPOCH:epoch-1")).toBeNull();
    expect(parseReportScopeKey("PRESET:30D|EPOCH:epoch-1|COHORT:CURRENT|EXTRA:value")).toBeNull();
  });

  it("labels empty and partial effective report periods without inventing dates", () => {
    const base = { from: "2026-07-01", toInclusive: "2026-07-03", timezone: "Asia/Tokyo", populationState: "PARTIAL_LIFECYCLE", effectiveDays: 2 };
    expect(reportEffectivePeriodLabel({ ...base, effectiveFrom: null, effectiveToInclusive: null })).toBe("No lifecycle overlap");
    expect(reportEffectivePeriodLabel({ ...base, effectiveFrom: "2026-07-02", effectiveToInclusive: "2026-07-03" })).toBe("2026-07-02 — 2026-07-03 · 2D");
  });

  it("never exposes pin eligibility across epoch/cohort or legacy unversioned scope", () => {
    const selected = { epochId: "epoch-1", cohort: "CURRENT", scopeKey: "PRESET:30D|EPOCH:epoch-1|COHORT:CURRENT" };
    expect(reportRevisionMatchesScope(selected, "PRESET:30D", "epoch-1", "CURRENT")).toBe(true);
    expect(reportRevisionMatchesScope({ ...selected, epochId: null, cohort: null, scopeKey: "PRESET:30D" }, "PRESET:30D", "epoch-1", "CURRENT")).toBe(false);
    expect(reportRevisionMatchesScope(selected, "PRESET:30D", "epoch-2", "CURRENT")).toBe(false);
    expect(reportRevisionMatchesScope(selected, "PRESET:30D", "epoch-1", "LEGACY_PRE_WS")).toBe(false);
  });

  it("surfaces rejected job identity and Retry-After", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify({
      ...job, status: "REJECTED", stage: "REJECTED", failureCode: "CONCURRENT_INVOCATION",
      activeInvocationId: "active-1", retryAfterSeconds: 15,
    }), { status: 409, headers: { "Content-Type": "application/json", "Retry-After": "15" } })));
    const progress = vi.fn();

    const error = await generateReport({ kind: "PRESET", days: 30 }, new AbortController().signal, progress, "epoch-1")
      .catch((cause: unknown) => cause);

    expect(error).toBeInstanceOf(ReportAdmissionError);
    expect((error as ReportAdmissionError).job.activeInvocationId).toBe("active-1");
    expect((error as ReportAdmissionError).retryAfter).toBe("15");
    expect(progress).toHaveBeenCalledOnce();
    expect(JSON.parse((vi.mocked(fetch).mock.calls[0]?.[1]?.body as string))).toMatchObject({
      epochId: "epoch-1", cohort: "CURRENT",
    });
  });

  it("reports every stage and stops at terminal success", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify(job), { status: 202, headers: { "Content-Type": "application/json" } }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ ...job, status: "RUNNING", stage: "VALIDATING" }), { status: 200, headers: { "Content-Type": "application/json" } }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ ...job, status: "SUCCEEDED", stage: "COMPLETE" }), { status: 200, headers: { "Content-Type": "application/json" } }));
    vi.stubGlobal("fetch", fetchMock);
    const progress = vi.fn();

    await generateReport({ kind: "PRESET", days: 30 }, new AbortController().signal, progress, "epoch-1");

    expect(progress.mock.calls.map(([value]) => value.stage)).toEqual(["ADMITTED", "VALIDATING", "COMPLETE"]);
  });
});
