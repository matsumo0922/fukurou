import { describe, expect, it } from "vitest";
import { filterEvidencePaths, projectEvidencePaths } from "./evidenceProjection";

describe("evidence projection", () => {
  it("projects every segment claim fact source chart relationship and filters the same rows", () => {
    const report = { segments: [{ segmentId: "s1", claimIds: ["c1"] }, { segmentId: "s2", claimIds: ["c1"] }], claims: [{ claimId: "c1", factIds: ["f1"] }], validation: [{ claimId: "c1", status: "VERIFIED" }], facts: [{ factId: "f1", sourceIds: ["source-a", "source-b"] }], sources: [], chartIndex: [{ chartId: "chart-a", factIds: ["f1"] }] } as never;
    const paths = projectEvidencePaths(report);
    expect(paths).toHaveLength(4);
    expect(new Set(paths.map((path) => path.segment))).toEqual(new Set(["s1", "s2"]));
    expect(filterEvidencePaths(paths, "source-b", "VERIFIED", "c1")).toHaveLength(2);
  });

  it("projects unavailable chart placeholder for graph and table consumers", () => {
    const report = { segments: [{ segmentId: "s1", claimIds: ["c1"] }], claims: [{ claimId: "c1", factIds: ["f1"] }], validation: [{ claimId: "c1", status: "CONFLICT" }], facts: [{ factId: "f1", sourceIds: [] }], sources: [], chartIndex: [] } as never;
    expect(projectEvidencePaths(report)).toEqual([{ segment: "s1", claim: "c1", status: "conflict", fact: "f1", source: "SOURCE_UNAVAILABLE", chart: "CHART_UNAVAILABLE" }]);
  });
});
