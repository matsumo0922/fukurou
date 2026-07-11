import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useMemo, useState } from "react";
import RefreshCw from "lucide-react/dist/esm/icons/refresh-cw.mjs";
import { generateReport, reportQuery, type EvaluationReport } from "../api/evaluationReport";
import { HistoricalOutcomeRidge } from "./evaluation-report/HistoricalOutcomeRidge";
import { LazyEvidenceRelationshipGraph } from "./evaluation-report/EvidenceRelationshipGraph.lazy";

export function EvaluationPage() {
  const [days, setDays] = useState(30);
  const queryClient = useQueryClient();
  const query = useQuery(reportQuery(days));
  const generation = useMutation({
    mutationFn: () => generateReport(days),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["evaluation-report", days] }),
  });

  return <main className="evaluation-console">
    <header className="console-header">
      <div><span className="console-kicker">EVALUATION / REPORT CONSOLE</span><h1>Auditable LLM evaluation</h1><p>Immutable report revisions and deterministic paper evidence share this screen without sharing authority.</p></div>
      <div className="console-actions" aria-label="Report period and generation">
        {[7, 30, 90].map((value) => <button key={value} className={days === value ? "is-active" : ""} onClick={() => setDays(value)}>{value}D</button>)}
        <button className="generate-button" disabled={generation.isPending} onClick={() => generation.mutate()}><RefreshCw size={15} aria-hidden />{generation.isPending ? "GENERATING" : "GENERATE REPORT"}</button>
      </div>
    </header>
    <CurrentContextStrip />
    {generation.isError && <div className="console-alert" role="alert">Generation failed: {generation.error.message}. Existing revision remains authoritative.</div>}
    {query.isPending ? <div className="console-empty">Loading immutable report revision…</div> : query.isError ? <div className="console-alert" role="alert">Report request failed: {query.error.message}</div> : query.data == null ? <EmptyReport onGenerate={() => generation.mutate()} /> : <ReportConsole report={query.data} />}
  </main>;
}

function CurrentContextStrip() {
  return <section className="current-context" aria-label="Current context">
    <div><span>CURRENT CONTEXT · NOT REPORT EVIDENCE</span><strong>READ-ONLY</strong></div>
    <div><span>Connection</span><strong>UNAVAILABLE</strong><small>No current-context WebSocket snapshot</small></div>
    <div><span>Market quote</span><strong>MISSING</strong><small>Never backfilled into report evidence</small></div>
    <div><span>Risk / mode</span><strong>PAPER</strong><small>Live trading is not enabled</small></div>
    <div><span>Freshness</span><strong>DISCONNECTED</strong><small>Historical revision remains unchanged</small></div>
  </section>;
}

function EmptyReport({ onGenerate }: { onGenerate: () => void }) {
  return <section className="console-empty"><h2>No immutable report revision</h2><p>Generate a report to snapshot eligible paper evidence. Missing data is retained as missing.</p><button className="generate-button" onClick={onGenerate}>GENERATE 30D REPORT</button></section>;
}

function ReportConsole({ report }: { report: EvaluationReport }) {
  const firstConflict = report.validation.find((result) => result.status === "CONFLICT")?.claimId;
  const [selectedClaim, setSelectedClaim] = useState<string | null>(firstConflict ?? report.claims[0]?.claimId ?? null);

  return <>
    <RevisionRail report={report} />
    <ReportStage report={report} selectedClaim={selectedClaim} onSelectClaim={setSelectedClaim} />
    <EvidenceSummary report={report} />
    <HistoricalOutcomeRidge report={report} />
    <LazyEvidenceRelationshipGraph report={report} selectedClaim={selectedClaim} onSelectClaim={setSelectedClaim} />
  </>;
}

function RevisionRail({ report }: { report: EvaluationReport }) {
  const verified = report.validation.filter((result) => result.status === "VERIFIED").length;
  return <section className="revision-rail" aria-label="Immutable report revision metadata">
    <div><span>Revision</span><strong>#{report.revisionNumber} · PINNED</strong><small>{report.scopeKey}</small></div>
    <div><span>Snapshot authority</span><strong>{report.snapshotId.slice(0, 12)}</strong><small>{report.inputHash.slice(0, 20)}</small></div>
    <div><span>Input as of</span><strong>{new Date(report.inputAsOf).toLocaleString()}</strong><small>{report.period.from} — {report.period.toInclusive}</small></div>
    <div><span>Generator</span><strong>{report.provider}</strong><small>{report.model}</small></div>
    <div><span>Claim coverage</span><strong>{verified}/{report.validation.length} verified</strong><small>{report.validation.filter((result) => result.status === "CONFLICT").length} conflict</small></div>
    <div><span>Snapshot</span><strong>{report.truncated ? "PARTIAL" : "COMPLETE"}</strong><small>immutable historical evidence</small></div>
  </section>;
}

function ReportStage({ report, selectedClaim, onSelectClaim }: { report: EvaluationReport; selectedClaim: string | null; onSelectClaim: (claimId: string) => void }) {
  const selected = report.claims.find((claim) => claim.claimId === selectedClaim);
  const validation = report.validation.find((result) => result.claimId === selectedClaim);
  const facts = useMemo(() => report.facts.filter((fact) => selected?.factIds.includes(fact.factId)), [report.facts, selected]);

  return <section className="report-stage report-panel" aria-labelledby="report-title">
    <header className="report-panel__header"><div><span className="console-kicker">PINNED REPORT ARTIFACT · EXPLANATION ONLY</span><h2 id="report-title">{report.title}</h2></div><span className="report-status">{report.validation.some((result) => result.status === "CONFLICT") ? "WARNING" : "VALIDATED"}</span></header>
    <div className="report-stage__grid"><div className="report-prose">{report.segments.map((segment) => {
      const results = report.validation.filter((result) => segment.claimIds.includes(result.claimId));
      const conflict = results.some((result) => result.status === "CONFLICT");
      return <article key={segment.segmentId} className={conflict ? "report-segment is-conflict" : "report-segment"}><span>{segment.kind}<br />{segment.segmentId}</span><p>{segment.text}</p><div>{segment.claimIds.map((claimId) => { const result = report.validation.find((item) => item.claimId === claimId); return <button key={claimId} className={result?.status === "CONFLICT" ? "claim-button is-conflict" : "claim-button"} onClick={() => onSelectClaim(claimId)}>{humanStatus(result?.status ?? "NOT_VERIFIABLE")}</button>; })}</div></article>;
    })}</div><aside className="claim-inspector"><span className="console-kicker">CLAIM INSPECTOR</span>{selected == null ? <p>No typed claim is bound.</p> : <><h3>{selected.claimId}</h3><dl><dt>Status</dt><dd>{humanStatus(validation?.status ?? "NOT_VERIFIABLE")}</dd><dt>Asserted</dt><dd><code>{validation?.asserted}</code></dd><dt>Actual</dt><dd><code>{validation?.actual ?? "insufficient evidence"}</code></dd><dt>Code</dt><dd>{validation?.code}</dd></dl>{facts.map((fact) => <div className="inspector-fact" key={fact.factId}><strong>{fact.factId}</strong><code>{fact.value ?? "missing"} {fact.unit}</code><small>{fact.availability} · {fact.sourceIds.join(", ")}</small></div>)}</>}</aside></div>
  </section>;
}

function EvidenceSummary({ report }: { report: EvaluationReport }) {
  const missing = report.facts.filter((fact) => fact.availability !== "AVAILABLE").length;
  const availableR = report.outcomeRidge.groupings.find((item) => item.groupBy === "SETUP")?.groups.reduce((sum, group) => sum + group.availableRCount, 0) ?? 0;
  return <section className="evidence-summary"><div><span>Deterministic facts</span><strong>{report.facts.length}</strong></div><div><span>Sources</span><strong>{report.sources.length}</strong></div><div><span>R available</span><strong>{availableR}</strong></div><div><span>Missing facts</span><strong>{missing}</strong></div><div><span>Coverage</span><strong>{report.truncated ? "PARTIAL SNAPSHOT" : "COMPLETE"}</strong></div></section>;
}

function humanStatus(status: string): string {
  return status.toLowerCase().replaceAll("_", " ");
}
