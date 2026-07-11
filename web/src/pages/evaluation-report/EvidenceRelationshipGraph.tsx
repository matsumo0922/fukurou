import dagre from "@dagrejs/dagre";
import { Background, Controls, ReactFlow, type Edge, type Node } from "@xyflow/react";
import "@xyflow/react/dist/style.css";
import { useMemo, useState } from "react";
import type { EvaluationReport } from "../../api/evaluationReport";

type GraphData = { nodes: Node[]; edges: Edge[]; paths: EvidencePath[] };
type EvidencePath = { segment: string; claim: string; validation: string; fact: string; source: string; chart: string };

export default function EvidenceRelationshipGraph({ report, selectedClaim, onSelectClaim }: { report: EvaluationReport; selectedClaim: string | null; onSelectClaim: (claimId: string) => void }) {
  const [showTable, setShowTable] = useState(false);
  const graph = useMemo(() => projectGraph(report, selectedClaim), [report, selectedClaim]);

  return <section className="report-panel report-panel--wide" aria-labelledby="evidence-graph-title">
    <header className="report-panel__header"><div><span className="console-kicker">EVIDENCE RELATIONSHIP GRAPH</span><h2 id="evidence-graph-title">Segment → claim → fact → source</h2></div><div className="console-badges"><span>REFERENCE GRAPH · NOT CAUSAL</span><code>{report.inputHash.slice(0, 12)}</code></div></header>
    <div className="evidence-graph" aria-label="Evidence relationship graph">
      <ReactFlow
        nodes={graph.nodes}
        edges={graph.edges}
        nodesDraggable={false}
        nodesConnectable={false}
        deleteKeyCode={null}
        fitView
        minZoom={0.45}
        onNodeClick={(_, node) => { if (node.id.startsWith("claim:")) onSelectClaim(node.id.slice(6)); }}
      ><Background gap={24} size={1} /><Controls showInteractive={false} /></ReactFlow>
    </div>
    <div className="console-toolbar"><button onClick={() => setShowTable((value) => !value)}>SHOW PATHS TABLE</button><span>Node size and edge width do not encode confidence, importance, profit or causality.</span></div>
    {showTable && <div className="console-table-wrap"><table><caption>Evidence relationship text representation</caption><thead><tr><th>Segment</th><th>Claim</th><th>Status</th><th>Fact</th><th>Source</th></tr></thead><tbody>{graph.paths.map((path, index) => <tr key={`${path.claim}:${path.fact}:${index}`}><td>{path.segment}</td><td>{path.claim}</td><td>{path.validation}</td><td>{path.fact}</td><td>{path.source || "SOURCE_UNAVAILABLE"}</td></tr>)}</tbody></table></div>}
  </section>;
}

function projectGraph(report: EvaluationReport, selectedClaim: string | null): GraphData {
  const nodes: Node[] = [];
  const edges: Edge[] = [];
  const paths: EvidencePath[] = [];
  const validation = new Map(report.validation.map((item) => [item.claimId, item.status]));
  const factById = new Map(report.facts.map((fact) => [fact.factId, fact]));
  const sourceIds = new Set(report.sources.map((source) => source.sourceId));

  report.segments.forEach((segment) => {
    nodes.push(makeNode(`segment:${segment.segmentId}`, segment.kind, "segment", 0));
    segment.claimIds.forEach((claimId) => edges.push(makeEdge(`segment:${segment.segmentId}`, `claim:${claimId}`, selectedClaim === claimId)));
  });
  report.claims.forEach((claim) => {
    const state = validation.get(claim.claimId) ?? "NOT_VERIFIABLE";
    nodes.push(makeNode(`claim:${claim.claimId}`, `${claim.claimId}\n${humanStatus(state)}`, `claim ${state.toLowerCase()}`, 1));
    claim.factIds.forEach((factId) => {
      edges.push(makeEdge(`claim:${claim.claimId}`, `fact:${factId}`, selectedClaim === claim.claimId));
      const fact = factById.get(factId);
      const sources = fact?.sourceIds.filter((sourceId) => sourceIds.has(sourceId)) ?? [];
      paths.push({ segment: report.segments.find((segment) => segment.claimIds.includes(claim.claimId))?.segmentId ?? "UNBOUND", claim: claim.claimId, validation: humanStatus(state), fact: factId, source: sources.join(", "), chart: "" });
    });
  });
  report.facts.forEach((fact) => {
    nodes.push(makeNode(`fact:${fact.factId}`, `${fact.factId}\n${fact.value ?? "missing"} ${fact.unit ?? ""}`, `fact ${fact.availability.toLowerCase()}`, 2));
    fact.sourceIds.filter((sourceId) => sourceIds.has(sourceId)).forEach((sourceId) => edges.push(makeEdge(`fact:${fact.factId}`, `source:${sourceId}`, false)));
  });
  report.sources.forEach((source) => nodes.push(makeNode(`source:${source.sourceId}`, `${source.sourceId}\n${source.freshness}`, "source", 3)));

  return layout(nodes, edges, paths);
}

function makeNode(id: string, label: string, className: string, layer: number): Node {
  return { id, data: { label }, position: { x: layer * 300, y: 0 }, className, width: 220, height: 64, ariaLabel: label.replace("\n", ", ") };
}

function makeEdge(source: string, target: string, selected: boolean): Edge {
  return { id: `${source}->${target}`, source, target, className: selected ? "selected" : undefined, animated: false, focusable: true };
}

function layout(nodes: Node[], edges: Edge[], paths: EvidencePath[]): GraphData {
  const graph = new dagre.graphlib.Graph().setDefaultEdgeLabel(() => ({}));
  graph.setGraph({ rankdir: "LR", ranksep: 90, nodesep: 28, edgesep: 12 });
  [...nodes].sort((left, right) => left.id.localeCompare(right.id)).forEach((node) => graph.setNode(node.id, { width: 220, height: 64 }));
  [...edges].sort((left, right) => left.id.localeCompare(right.id)).forEach((edge) => graph.setEdge(edge.source, edge.target));
  dagre.layout(graph);

  return { nodes: nodes.map((node) => { const position = graph.node(node.id); return { ...node, position: { x: position.x - 110, y: position.y - 32 } }; }), edges, paths };
}

function humanStatus(status: string): string {
  return status.toLowerCase().replaceAll("_", " ");
}
