import type { EvaluationReport } from "../../api/evaluationReport";
import { projectEvidencePaths, type EvidencePath } from "./evidenceProjection";

export function EvidencePathsTable({ report, paths = projectEvidencePaths(report) }: { report: EvaluationReport; paths?: EvidencePath[] }) {
  return <div className="console-table-wrap"><table><caption>Evidence relationship text representation; authoritative when the graph canvas is unavailable</caption><thead><tr><th>Segment</th><th>Claim</th><th>Status</th><th>Fact</th><th>Source</th><th>Chart</th></tr></thead><tbody>{paths.map((path, index) => <tr key={`${path.segment}:${path.claim}:${path.fact}:${path.source}:${path.chart}:${index}`}><td>{path.segment}</td><td>{path.claim}</td><td>{path.status}</td><td>{path.fact}</td><td>{path.source}</td><td>{path.chart}</td></tr>)}</tbody></table></div>;
}
