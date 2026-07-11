import { describe, expect, it } from "vitest";
import { filterEvidencePaths, projectEvidencePaths } from "./evidenceProjection";

describe("evidence projection", () => {
  it("projects every segment claim fact source chart relationship and filters the same rows", () => {
    const report = { segments: [{ segmentId: "s1", claimIds: ["c1"] }, { segmentId: "s2", claimIds: ["c1"] }], claims: [{ claimId: "c1", factIds: ["f1"] }], validation: [{ claimId: "c1", status: "VALIDATED" }], facts: [{ factId: "f1", sourceIds: ["source-a", "source-b"] }], sources: [], chartIndex: [{ chartId: "chart-a", factIds: ["f1"] }] } as never;
    const paths = projectEvidencePaths(report);
    expect(paths).toHaveLength(4);
    expect(new Set(paths.map((path) => path.segment))).toEqual(new Set(["s1", "s2"]));
    expect(filterEvidencePaths(paths, "source-b", "VALIDATED", "c1")).toHaveLength(2);
  });
});
