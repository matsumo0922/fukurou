import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import type { EvaluationReport } from "../../api/evaluationReport";

vi.mock("./EvidenceRelationshipGraph", () => { throw new Error("chunk rejected"); });
import { LazyEvidenceRelationshipGraph } from "./EvidenceRelationshipGraph.lazy";

describe("LazyEvidenceRelationshipGraph", () => {
  it("keeps the synchronized path table when the graph chunk rejects", async () => {
    const report = { segments: [{ segmentId: "segment-1", claimIds: ["claim-1"] }], claims: [{ claimId: "claim-1", factIds: ["fact-1"] }], validation: [{ claimId: "claim-1", status: "VERIFIED" }], facts: [{ factId: "fact-1", sourceIds: [] }], sources: [], chartIndex: [] } as unknown as EvaluationReport;
    render(<LazyEvidenceRelationshipGraph report={report} selectedClaim={null} onSelectClaim={() => undefined} />);
    expect(await screen.findByRole("alert")).toHaveTextContent("Graph canvas unavailable");
    expect(screen.getByRole("table")).toHaveTextContent("CHART_UNAVAILABLE");
  });
});
