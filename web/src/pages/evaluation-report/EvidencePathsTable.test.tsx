import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { EvidencePathsTable } from "./EvidencePathsTable";
import type { EvaluationReport } from "../../api/evaluationReport";

describe("EvidencePathsTable", () => {
  it("keeps source and chart paths available without the graph chunk", () => {
    const report = {
      segments: [{ segmentId: "segment-1", kind: "SUMMARY", text: "text", claimIds: ["claim-1"] }],
      claims: [{ claimId: "claim-1", type: "FACT_VALUE", factIds: ["fact-1"], asserted: "1" }],
      validation: [{ claimId: "claim-1", status: "VERIFIED", asserted: "1", actual: "1", factIds: ["fact-1"], code: "MATCH" }],
      facts: [{ factId: "fact-1", value: "1", unit: "COUNT", availability: "AVAILABLE", sourceIds: ["source-1"] }],
      sources: [{ sourceId: "source-1", observedAt: "2026-07-12T00:00:00Z", freshness: "SNAPSHOT" }],
      chartIndex: [{ chartId: "chart-1", catalogVersion: "v1", factIds: ["fact-1"] }],
    } as EvaluationReport;

    render(<EvidencePathsTable report={report} />);

    expect(screen.getByRole("table", { name: /authoritative when the graph canvas is unavailable/ })).toBeInTheDocument();
    expect(screen.getByText("source-1")).toBeInTheDocument();
    expect(screen.getByText("chart-1")).toBeInTheDocument();
  });
});
