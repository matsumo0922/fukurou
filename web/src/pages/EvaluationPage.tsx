import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useMemo, useState } from "react";
import RefreshCw from "lucide-react/dist/esm/icons/refresh-cw.mjs";
import { fetchReportHistory, fetchReportRevision, generateReport, pinReport, reportQuery, reportScopeKey, type EvaluationReport, type ReportScope } from "../api/evaluationReport";
import { HistoricalOutcomeRidge } from "./evaluation-report/HistoricalOutcomeRidge";
import { LazyEvidenceRelationshipGraph } from "./evaluation-report/EvidenceRelationshipGraph.lazy";

export function EvaluationPage() {
  const [days, setDays] = useState(30);
  const [custom, setCustom] = useState(false);
  const [customFrom, setCustomFrom] = useState("");
  const [customTo, setCustomTo] = useState("");
  const scope: ReportScope = custom ? { kind: "CUSTOM", from: customFrom, toInclusive: customTo } : { kind: "PRESET", days: days as 7 | 30 | 90 };
  const scopeKey = reportScopeKey(scope);
  const queryClient = useQueryClient();
  const query = useQuery({ ...reportQuery(scopeKey), enabled: !custom || Boolean(customFrom && customTo) });
  const history = useQuery({ queryKey: ["evaluation-report-history", scopeKey], queryFn: () => fetchReportHistory(scopeKey), enabled: !custom || Boolean(customFrom && customTo) });
  const [preview, setPreview] = useState<EvaluationReport | null>(null);
  const generation = useMutation({
    mutationFn: () => generateReport(scope),
    onSuccess: () => Promise.all([
      queryClient.invalidateQueries({ queryKey: ["evaluation-report", scopeKey] }),
      queryClient.invalidateQueries({ queryKey: ["evaluation-report-history", scopeKey] }),
    ]),
  });

  return <main className="evaluation-console">
    <header className="console-header">
      <div><span className="console-kicker">EVALUATION / REPORT CONSOLE</span><h1>Auditable LLM evaluation</h1><p>Immutable report revisions and deterministic paper evidence share this screen without sharing authority.</p></div>
      <div className="console-actions" aria-label="Report period and generation">
        {[7, 30, 90].map((value) => <button key={value} className={!custom && days === value ? "is-active" : ""} onClick={() => { setCustom(false); setDays(value); }}>{value}D</button>)}
        <button className={custom ? "is-active" : ""} onClick={() => setCustom(true)}>CUSTOM</button>
        {custom && <><label>From<input type="date" value={customFrom} onChange={(event) => setCustomFrom(event.target.value)} /></label><label>To<input type="date" value={customTo} onChange={(event) => setCustomTo(event.target.value)} /></label></>}
        <button className="generate-button" disabled={generation.isPending || (custom && (!customFrom || !customTo))} onClick={() => generation.mutate()}><RefreshCw size={15} aria-hidden />{generation.isPending ? "GENERATING" : "GENERATE REPORT"}</button>
      </div>
    </header>
    <CurrentContextStrip />
    {generation.isError && <div className="console-alert" role="alert">Generation failed: {generation.error.message}. Existing revision remains authoritative.</div>}
    {query.isPending ? <div className="console-empty">Loading immutable report revision…</div> : query.isError ? <div className="console-alert" role="alert">Report request failed: {query.error.message}</div> : (preview ?? query.data) == null ? <EmptyReport onGenerate={() => generation.mutate()} /> : <ReportConsole report={(preview ?? query.data)!} />}
    <section className="report-panel" aria-labelledby="report-history-title"><header className="report-panel__header"><div><span className="console-kicker">IMMUTABLE REVISION HISTORY</span><h2 id="report-history-title">Reports / failed jobs</h2></div></header><div className="console-table-wrap"><table><thead><tr><th>Revision</th><th>Status</th><th>Requested</th><th>Default</th><th>Actions</th></tr></thead><tbody>{history.data?.map((item) => <tr key={item.jobId}><td>#{item.revisionNumber || "—"}</td><td>{item.status}</td><td>{new Date(item.requestedAt).toLocaleString()}</td><td>{item.pinned ? "PINNED" : "—"}</td><td>{item.status === "SUCCEEDED" && <><button onClick={() => void fetchReportRevision(item.revisionId).then(setPreview)}>PREVIEW</button><button onClick={() => void pinReport(scopeKey, item.revisionId).then(() => queryClient.invalidateQueries({ queryKey: ["evaluation-report", scopeKey] }))}>PIN</button></>}</td></tr>)}</tbody></table></div></section>
  </main>;
}

function CurrentContextStrip() {
  const [context, setContext] = useState<{ state: string; sequence: number | null; sources: { source: string; freshness: string; value: Record<string, string> | null }[] }>({ state: "CONNECTING", sequence: null, sources: [] });
  useEffect(() => {
    if (typeof WebSocket === "undefined") return undefined;
    const protocol = window.location.protocol === "https:" ? "wss" : "ws";
    const socket = new WebSocket(`${protocol}://${window.location.host}/ops/current-context/ws`);
    socket.onmessage = (event) => {
      const envelope = JSON.parse(String(event.data)) as { sequence: number; sources: { source: string; freshness: string; value: Record<string, string> | null }[] };
      setContext((current) => current.sequence != null && envelope.sequence !== current.sequence + 1 ? { state: "RESYNCING", sequence: envelope.sequence, sources: envelope.sources } : { state: "CONNECTED", sequence: envelope.sequence, sources: envelope.sources });
    };
    socket.onclose = () => setContext((current) => ({ ...current, state: "DISCONNECTED" }));
    socket.onerror = () => setContext((current) => ({ ...current, state: "DISCONNECTED" }));
    return () => socket.close();
  }, []);
  const quote = context.sources.find((source) => source.source === "MARKET_QUOTE");
  const runtime = context.sources.find((source) => source.source === "RUNTIME_STATE");
  const account = context.sources.find((source) => source.source === "PAPER_ACCOUNT");
  const latestRun = context.sources.find((source) => source.source === "LATEST_LLM_RUN");

  return <section className="current-context" aria-label="Current context">
    <div><span>CURRENT CONTEXT · NOT REPORT EVIDENCE</span><strong>READ-ONLY</strong></div>
    <div><span>Connection</span><strong>{context.state}</strong><small>sequence {context.sequence ?? "—"}</small></div>
    <div><span>Market quote</span><strong>{quote?.value?.bidPriceJpy ?? "MISSING"}</strong><small>{quote?.freshness ?? "UNAVAILABLE"} · never report evidence</small></div>
    <div><span>Risk / mode</span><strong>{runtime?.value?.riskState ?? "PAPER"}</strong><small>{runtime?.value?.mode ?? "PAPER"} · live trading is not enabled</small></div>
    <div><span>Paper equity / exposure</span><strong>{account?.value?.equityJpy ?? "UNAVAILABLE"}</strong><small>{account?.value?.exposureJpy ?? "UNAVAILABLE"} JPY exposure</small></div>
    <div><span>Latest LLM run</span><strong>{latestRun?.value?.status ?? "UNAVAILABLE"}</strong><small>{latestRun?.value?.invocationId ?? "no persisted run"}</small></div>
    <div><span>Freshness</span><strong>{quote?.freshness ?? "UNAVAILABLE"}</strong><small>Historical revision remains unchanged</small></div>
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
