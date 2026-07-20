import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useMemo, useRef, useState } from "react";
import RefreshCw from "lucide-react/dist/esm/icons/refresh-cw.mjs";
import { fetchEvaluationEpochs, fetchReportHistory, fetchReportRevision, generateReport, pinReport, reportEffectivePeriodLabel, reportQuery, reportRevisionMatchesScope, reportScopeKey, type EvaluationReport, type ReportJob, type ReportScope } from "../api/evaluationReport";
import { LazyHistoricalOutcomeRidge } from "./evaluation-report/HistoricalOutcomeRidge.lazy";
import { LazyEvidenceRelationshipGraph } from "./evaluation-report/EvidenceRelationshipGraph.lazy";
import { initialContextState } from "./evaluation-report/currentContextStateMachine";
import { startCurrentContextClient } from "./evaluation-report/currentContextClient";
import { evaluationBenchmarkQuery, type EvaluationBenchmarkResponse } from "../api/ops";

export function EvaluationPage() {
  const [days, setDays] = useState(30);
  const [custom, setCustom] = useState(false);
  const [customFrom, setCustomFrom] = useState("");
  const [customTo, setCustomTo] = useState("");
  const [cohort, setCohort] = useState<"CURRENT" | "LEGACY_PRE_WS" | "UNSUPPORTED_EXECUTION_SEMANTICS">("CURRENT");
  const epochs = useQuery({ queryKey: ["evaluation-epochs"], queryFn: fetchEvaluationEpochs, staleTime: 30_000 });
  const [epochId, setEpochId] = useState("");
  const selectedEpochId = epochId || epochs.data?.find((epoch) => epoch.active)?.epochId || epochs.data?.[0]?.epochId || "";
  const scope: ReportScope = custom ? { kind: "CUSTOM", from: customFrom, toInclusive: customTo } : { kind: "PRESET", days: days as 7 | 30 | 90 };
  const scopeKey = reportScopeKey(scope);
  const queryClient = useQueryClient();
  const scopeReady = Boolean(selectedEpochId) && (!custom || Boolean(customFrom && customTo));
  const query = useQuery({ ...reportQuery(scopeKey, selectedEpochId, cohort), enabled: scopeReady });
  const history = useQuery({ queryKey: ["evaluation-report-history", scopeKey, selectedEpochId, cohort], queryFn: () => fetchReportHistory(scopeKey, selectedEpochId, cohort), enabled: scopeReady });
  const [preview, setPreview] = useState<{ identity: string; report: EvaluationReport } | null>(null);
  const [generationJob, setGenerationJob] = useState<ReportJob | null>(null);
  const generationAbort = useRef<AbortController | null>(null);
  useEffect(() => () => generationAbort.current?.abort(), []);
  useEffect(() => () => generationAbort.current?.abort(), [scopeKey, selectedEpochId, cohort]);
  const generation = useMutation({
    mutationFn: () => {
      generationAbort.current?.abort();
      generationAbort.current = new AbortController();
      return generateReport(scope, generationAbort.current.signal, setGenerationJob, selectedEpochId, cohort);
    },
    onSuccess: () => {
      setPreview(null);
      return Promise.all([
        queryClient.invalidateQueries({ queryKey: ["evaluation-report", scopeKey, selectedEpochId, cohort] }),
        queryClient.invalidateQueries({ queryKey: ["evaluation-report-history", scopeKey, selectedEpochId, cohort] }),
      ]);
    },
  });
  const identity = `${scopeKey}|${selectedEpochId}|${cohort}`;
  const displayedReport = preview?.identity === identity ? preview.report : query.data;
  const displayedIsPinned = history.data?.some((item) => item.pinned && item.revisionId === displayedReport?.revisionId) ?? false;

  return <main className="evaluation-console">
    <header className="console-header">
      <div><span className="console-kicker">EVALUATION / REPORT CONSOLE</span><h1>Auditable LLM evaluation</h1><p>Immutable report revisions and deterministic paper evidence share this screen without sharing authority.</p></div>
      <div className="console-actions" aria-label="Report period and generation">
        {[7, 30, 90].map((value) => <button key={value} className={!custom && days === value ? "is-active" : ""} onClick={() => { setCustom(false); setDays(value); }}>{value}D</button>)}
        <button className={custom ? "is-active" : ""} onClick={() => setCustom(true)}>CUSTOM</button>
        <label>Epoch<select value={selectedEpochId} onChange={(event) => { setPreview(null); setEpochId(event.target.value); }}>{epochs.data?.map((epoch) => <option value={epoch.epochId} key={epoch.epochId}>{epoch.active ? "ACTIVE · " : ""}{epoch.kind} · {epoch.initialCashJpy} JPY · {epoch.epochId.slice(0, 8)}</option>)}</select></label>
        <label>Cohort<select value={cohort} onChange={(event) => setCohort(event.target.value as typeof cohort)}><option value="CURRENT">CURRENT</option><option value="LEGACY_PRE_WS">LEGACY / REFERENCE</option><option value="UNSUPPORTED_EXECUTION_SEMANTICS">UNSUPPORTED / NOT EVALUABLE</option></select></label>
        {custom && <><label>From<input type="date" value={customFrom} onChange={(event) => setCustomFrom(event.target.value)} /></label><label>To<input type="date" value={customTo} onChange={(event) => setCustomTo(event.target.value)} /></label></>}
        <button className="generate-button" disabled={generation.isPending || !scopeReady} onClick={() => generation.mutate()}><RefreshCw size={15} aria-hidden />{generation.isPending ? "GENERATING" : "GENERATE REPORT"}</button>
      </div>
    </header>
    {cohort === "LEGACY_PRE_WS" && <div className="console-alert" role="status">Legacy pre-WebSocket trades are reference-only. Baseline benchmark series and returns are not comparable.</div>}
    {cohort === "UNSUPPORTED_EXECUTION_SEMANTICS" && <div className="console-alert" role="alert">Unsupported execution semantics are not evaluable. Coverage and missing attribution remain visible, but results must not be treated as CURRENT performance.</div>}
    <CurrentContextStrip />
    <OwnerScoreCard />
    {generationJob && generation.isPending && <div className="console-alert" role="status">Job {generationJob.jobId.slice(0, 12)} · revision #{generationJob.revisionNumber} · {generationJob.stage}. Existing pinned revision remains authoritative.</div>}
    {generation.isError && <div className="console-alert" role="alert">Generation failed: {generation.error.message}. Existing revision remains authoritative.</div>}
    {query.isPending ? <div className="console-empty">Loading immutable report revision…</div> : query.isError ? <div className="console-alert" role="alert">Report request failed: {query.error.message}</div> : displayedReport == null ? <EmptyReport onGenerate={() => generation.mutate()} /> : <ReportConsole report={displayedReport} pinned={displayedIsPinned} />}
    <section className="report-panel" aria-labelledby="report-history-title"><header className="report-panel__header"><div><span className="console-kicker">IMMUTABLE REVISION HISTORY</span><h2 id="report-history-title">Reports / failed jobs</h2></div></header><div className="console-table-wrap"><table><thead><tr><th>Revision</th><th>Scope</th><th>Status</th><th>Requested</th><th>Default</th><th>Actions</th></tr></thead><tbody>{history.data?.map((item) => { const scopeMatches = reportRevisionMatchesScope(item, scopeKey, selectedEpochId, cohort); return <tr key={item.jobId}><td>#{item.revisionNumber || "—"}</td><td>{item.epochId?.slice(0, 8) ?? "legacy"} · {item.cohort ?? "unversioned"}</td><td>{item.status}</td><td>{new Date(item.requestedAt).toLocaleString()}</td><td>{item.pinned ? "PINNED" : "—"}</td><td>{item.status === "SUCCEEDED" && scopeMatches && <><button onClick={() => void fetchReportRevision(item.revisionId, scopeKey, selectedEpochId, cohort).then((report) => setPreview({ identity, report }))}>PREVIEW</button><button onClick={() => void pinReport(scopeKey, item.revisionId, selectedEpochId, cohort).then(() => { setPreview(null); return queryClient.invalidateQueries({ queryKey: ["evaluation-report", scopeKey, selectedEpochId, cohort] }); })}>PIN</button></>}</td></tr>; })}</tbody></table></div></section>
  </main>;
}

function CurrentContextStrip() {
  const [context, setContext] = useState(initialContextState);
  useEffect(() => {
    if (typeof WebSocket === "undefined") return undefined;
    const protocol = window.location.protocol === "https:" ? "wss" : "ws";
    return startCurrentContextClient({
      url: `${protocol}://${window.location.host}/ops/current-context/ws`,
      createSocket: (url) => new WebSocket(url),
      onContext: setContext,
    });
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

function OwnerScoreCard() {
  const query = useQuery(evaluationBenchmarkQuery);
  if (query.isPending) return <section className="report-panel"><p>Loading OWNER_SCORE_V1…</p></section>;
  if (query.isError) return <section className="console-alert" role="alert">Owner score request failed: {query.error.message}</section>;
  const benchmark = query.data;

  return <section className="report-panel" aria-labelledby="owner-score-title">
    <header className="report-panel__header"><div><span className="console-kicker">CURRENT OWNER SCORE · {benchmark.cutoffMode}</span><h2 id="owner-score-title">90-day liquidation benchmark</h2><p>{benchmark.semanticsVersion} · cutoff {new Date(benchmark.cutoff).toLocaleString()} · 06:00 JST boundary</p></div><span className="report-status">{benchmark.state}</span></header>
    <div className="evidence-summary"><div><span>Winner</span><strong>{benchmark.winner ?? "INCONCLUSIVE"}</strong></div><div><span>Owner score</span><strong>{benchmark.ownerScore ?? "—"}</strong></div><div><span>Coverage</span><strong>{benchmark.coverage.validDays}/{benchmark.coverage.expectedDays}</strong><small>{benchmark.coverage.unknownDays} unknown</small></div><div><span>Persisted gaps</span><strong>{benchmark.coverage.gapDays} days</strong><small>{benchmark.coverage.gapSeconds}s · {benchmark.coverage.gapCount} observations</small></div><div><span>Synthetic fee</span><strong>{Number(benchmark.syntheticTakerFeeRate) * 100}%</strong><small>entry/exit comparison</small></div></div>
    <p>{benchmark.feeBiasDisclosureJa}</p>
    <OwnerScoreChart benchmark={benchmark} />
    {benchmark.state === "INCONCLUSIVE" && <p>Coverage reasons: {Object.entries(benchmark.coverage.reasonCounts).map(([reason, count]) => `${reason} ${count}`).join(" · ") || "no valid boundary evidence"}</p>}
    <details><summary>Daily evidence ({benchmark.points.length} expected slots)</summary><div className="console-table-wrap"><table><caption>Unknown days remain chart gaps</caption><thead><tr><th>Close</th><th>State</th><th>Bot liquidation JPY</th><th>Buy &amp; hold JPY</th><th>Cash JPY</th></tr></thead><tbody>{benchmark.points.map((point) => <tr key={point.closeAt}><td>{point.date}</td><td>{point.state}</td><td>{point.botLiquidationEquityJpy ?? "gap"}</td><td>{point.buyAndHoldEquityJpy ?? "gap"}</td><td>{point.cashEquityJpy ?? "gap"}</td></tr>)}</tbody></table></div></details>
  </section>;
}

function OwnerScoreChart({ benchmark }: { benchmark: EvaluationBenchmarkResponse }) {
  const fields = ["botLiquidationEquityJpy", "buyAndHoldEquityJpy", "cashEquityJpy"] as const;
  const values = benchmark.points.flatMap((point) => fields.flatMap((field) => point[field] == null ? [] : [Number(point[field])])).filter(Number.isFinite);
  if (values.length < 2) return <p>Insufficient coverage: liquidation series is unavailable.</p>;
  const min = Math.min(...values); const max = Math.max(...values); const span = Math.max(max - min, 1);
  const segments = (field: typeof fields[number]) => {
    const result: string[][] = []; let current: string[] = [];
    benchmark.points.forEach((point, index) => {
      const raw = point[field];
      if (raw == null) { if (current.length > 1) result.push(current); current = []; return; }
      current.push(`${(index / Math.max(benchmark.points.length - 1, 1)) * 100},${60 - ((Number(raw) - min) / span) * 50}`);
    });
    if (current.length > 1) result.push(current);
    return result;
  };
  return <><svg viewBox="0 0 100 65" role="img" aria-labelledby="owner-score-chart-title owner-score-chart-desc"><title id="owner-score-chart-title">Owner score liquidation series</title><desc id="owner-score-chart-desc">Unknown business days are rendered as gaps. Blue is bot liquidation, amber is buy and hold, gray is cash.</desc>{segments("botLiquidationEquityJpy").map((segment, index) => <polyline key={`bot-${index}`} points={segment.join(" ")} fill="none" stroke="currentColor" strokeWidth="1.5" />)}{segments("buyAndHoldEquityJpy").map((segment, index) => <polyline key={`hold-${index}`} points={segment.join(" ")} fill="none" stroke="#c78b2c" strokeWidth="1.2" />)}{segments("cashEquityJpy").map((segment, index) => <polyline key={`cash-${index}`} points={segment.join(" ")} fill="none" stroke="#777" strokeWidth="1" />)}</svg><p>Legend: bot liquidation (blue/current color), fee-inclusive buy &amp; hold (amber), cash (gray). Unknown days are gaps.</p></>;
}

function EmptyReport({ onGenerate }: { onGenerate: () => void }) {
  return <section className="console-empty"><h2>No immutable report revision</h2><p>Generate a report to snapshot eligible paper evidence. Missing data is retained as missing.</p><button className="generate-button" onClick={onGenerate}>GENERATE 30D REPORT</button></section>;
}

function ReportConsole({ report, pinned }: { report: EvaluationReport; pinned: boolean }) {
  const firstConflict = report.validation.find((result) => result.status === "CONFLICT")?.claimId;
  const [selectedClaim, setSelectedClaim] = useState<string | null>(firstConflict ?? report.claims[0]?.claimId ?? null);

  return <>
    <RevisionRail report={report} pinned={pinned} />
    <ReportStage report={report} pinned={pinned} selectedClaim={selectedClaim} onSelectClaim={setSelectedClaim} />
    <EvidenceSummary report={report} />
    <DeterministicEvidenceBoard report={report} />
    <LazyHistoricalOutcomeRidge report={report} />
    <LazyEvidenceRelationshipGraph report={report} selectedClaim={selectedClaim} onSelectClaim={setSelectedClaim} />
  </>;
}

function RevisionRail({ report, pinned }: { report: EvaluationReport; pinned: boolean }) {
  const verified = report.validation.filter((result) => result.status === "VERIFIED").length;
  return <section className="revision-rail" aria-label="Immutable report revision metadata">
    <div><span>Revision</span><strong>#{report.revisionNumber} · {pinned ? "PINNED" : "PREVIEW"}</strong><small>{report.scopeKey}</small></div>
    <div><span>Snapshot authority</span><strong>{report.snapshotId.slice(0, 12)}</strong><small>{report.inputHash.slice(0, 20)}</small></div>
    <div><span>Input as of</span><strong>{new Date(report.inputAsOf).toLocaleString()}</strong><small>{report.period.from} — {report.period.toInclusive}</small></div>
    <div><span>Effective population</span><strong>{report.period.populationState}</strong><small>{reportEffectivePeriodLabel(report.period)}</small></div>
    <div><span>Generator</span><strong>{report.generation.provider}</strong><small>{report.generation.observedModels?.join(", ") || report.model} · {report.generation.effort}</small></div>
    <div><span>LLM cost</span><strong>{report.generation.totalCostUsd ? `$${report.generation.totalCostUsd}` : "UNPRICED"}</strong><small>{report.generation.durationMillis == null ? "duration unavailable" : `${report.generation.durationMillis}ms`} · {report.generation.schemaVersion}</small></div>
    <div><span>Claim coverage</span><strong>{verified}/{report.validation.length} verified</strong><small>{report.validation.filter((result) => result.status === "CONFLICT").length} conflict</small></div>
    <div><span>Snapshot</span><strong>{report.truncated ? "PARTIAL" : "COMPLETE"}</strong><small>immutable historical evidence</small></div>
  </section>;
}

function ReportStage({ report, pinned, selectedClaim, onSelectClaim }: { report: EvaluationReport; pinned: boolean; selectedClaim: string | null; onSelectClaim: (claimId: string) => void }) {
  const selected = report.claims.find((claim) => claim.claimId === selectedClaim);
  const validation = report.validation.find((result) => result.claimId === selectedClaim);
  const facts = useMemo(() => report.facts.filter((fact) => selected?.factIds.includes(fact.factId)), [report.facts, selected]);

  return <section className="report-stage report-panel" aria-labelledby="report-title">
    <header className="report-panel__header"><div><span className="console-kicker">{pinned ? "PINNED" : "HISTORY PREVIEW"} REPORT ARTIFACT · EXPLANATION ONLY</span><h2 id="report-title">{report.title}</h2></div><span className="report-status">{report.validation.some((result) => result.status === "CONFLICT") ? "WARNING" : "VALIDATED"}</span></header>
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
  return <section className="evidence-summary"><div><span>Deterministic facts</span><strong>{report.facts.length}</strong></div><div><span>Sources</span><strong>{report.sources.length}</strong></div><div><span>R available</span><strong>{availableR}</strong></div><div><span>Missing facts</span><strong>{missing}</strong></div><div><span>Attribution</span><strong>{report.attributionCoverage == null ? "UNAVAILABLE" : `${report.attributionCoverage.attributed}/${report.attributionCoverage.total}`}</strong><small>{report.attributionCoverage?.missing ?? "—"} missing</small></div><div><span>Coverage</span><strong>{report.truncated ? "PARTIAL SNAPSHOT" : "COMPLETE"}</strong></div></section>;
}

function DeterministicEvidenceBoard({ report }: { report: EvaluationReport }) {
  const setupCalibration = report.calibration.cells.filter((cell) => cell.groupBy === "SETUP" && cell.sampleCount > 0);
  const realizedGroups = report.outcomeRidge.groupings.find((grouping) => grouping.groupBy === "SETUP")?.groups ?? [];

  return <section className="report-panel" aria-labelledby="deterministic-evidence-title">
    <header className="report-panel__header"><div><span className="console-kicker">DETERMINISTIC SNAPSHOT VISUALIZATIONS</span><h2 id="deterministic-evidence-title">Performance, calibration and integrity</h2><p>Authority: immutable closed paper trades, daily candles and persisted runner usage. These panels are descriptive, not forecasts.</p></div></header>
    <div className="report-stage__grid">
      <article><h3>Legacy realized benchmark equity</h3><p>Immutable report fact (not OWNER_SCORE_V1) · JPY · {report.benchmark.points.length} daily observations · {report.benchmark.state}</p><EquityChart report={report} /><table><caption>Legacy realized equity series accessible fallback</caption><thead><tr><th>Date</th><th>Bot realized JPY</th><th>Buy &amp; hold JPY</th><th>No trade JPY</th></tr></thead><tbody>{report.benchmark.points.map((point) => <tr key={point.date}><td>{point.date}</td><td>{point.botEquityJpy}</td><td>{point.buyAndHoldEquityJpy}</td><td>{point.noTradeEquityJpy}</td></tr>)}</tbody></table></article>
      <article><h3>Forecast calibration probability lattice</h3><p>{report.calibration.unit} · {report.calibration.authority} · {report.calibration.state}</p>{setupCalibration.length === 0 ? <p>Insufficient sample: no closed trade has a usable forecast probability.</p> : <table><caption>Forecast probability against realized win rate</caption><thead><tr><th>Setup / bin</th><th>Forecast p</th><th>Realized win rate</th><th>n / state</th></tr></thead><tbody>{setupCalibration.map((cell) => <tr key={`${cell.groupKey}-${cell.lowerBoundInclusive}`}><td>{cell.groupKey} [{cell.lowerBoundInclusive}, {cell.upperBound}]</td><td>{cell.averageForecastProbability ?? "missing"}</td><td>{cell.realizedWinRate ?? "insufficient"}</td><td>{cell.sampleCount} / {cell.state}</td></tr>)}</tbody></table>}</article>
      <article><h3>Setup × market regime performance lattice</h3><p>{report.performanceLattice.unit} · {report.performanceLattice.authority} · {report.performanceLattice.state}</p>{report.performanceLattice.cells.length === 0 ? <p>Insufficient sample: there are no eligible closed trades.</p> : <table><caption>Expected realized R by setup and market regime</caption><thead><tr><th>Setup</th><th>Regime</th><th>Expected R</th><th>PnL JPY</th><th>n / state</th></tr></thead><tbody>{report.performanceLattice.cells.map((cell) => <tr key={`${cell.setup}-${cell.marketRegime}`}><td>{cell.setup}</td><td>{cell.marketRegime}</td><td>{cell.expectedR ?? "missing R"}</td><td>{cell.totalPnlJpy}</td><td>{cell.tradeCount} / {cell.state}</td></tr>)}</tbody></table>}</article>
      <article><h3>Evidence integrity / coverage and cost</h3><p>Snapshot authority · missing and exclusions remain explicit</p><dl><dt>Eligible trades</dt><dd>{report.integrity.eligibleTradeCount}</dd><dt>Missing realized R</dt><dd>{report.integrity.missingRCount}</dd><dt>Excluded order / position / run</dt><dd>{report.integrity.excludedOrderCount} / {report.integrity.excludedPositionCount} / {report.integrity.excludedDecisionRunCount}</dd><dt>LLM usage phases</dt><dd>{report.integrity.llmPhaseCount} ({report.integrity.missingUsagePhaseCount} missing usage, {report.integrity.unpricedPhaseCount} unpriced)</dd><dt>Known cost USD</dt><dd>{report.integrity.knownCostUsd ?? "unavailable"}{report.integrity.usageTruncated ? " · partial" : ""}</dd></dl>{Object.keys(report.integrity.exclusionReasons).length === 0 ? <p>No persisted exclusion reason in this period.</p> : <ul>{Object.entries(report.integrity.exclusionReasons).map(([reason, count]) => <li key={reason}>{reason}: {count}</li>)}</ul>}</article>
      <article><h3>Realized R outcome summary</h3><p>R multiple · observed closed trades only · separate summary of ridge facts</p>{realizedGroups.length === 0 ? <p>Insufficient sample: no setup distribution exists.</p> : <table><caption>Realized R availability and median by setup</caption><thead><tr><th>Setup</th><th>Median R</th><th>Available / missing</th><th>Sample state</th></tr></thead><tbody>{realizedGroups.map((group) => <tr key={group.groupKey}><td>{group.label}</td><td>{group.medianR ?? "insufficient"}</td><td>{group.availableRCount} / {group.missingRCount}</td><td>{group.sampleState}</td></tr>)}</tbody></table>}</article>
    </div>
  </section>;
}

function EquityChart({ report }: { report: EvaluationReport }) {
  const points = report.benchmark.points;
  if (points.length < 2) return <p>Insufficient sample: at least two daily observations are required.</p>;
  const values = points.flatMap((point) => [Number(point.botEquityJpy), Number(point.buyAndHoldEquityJpy), Number(point.noTradeEquityJpy)]).filter(Number.isFinite);
  const min = Math.min(...values); const max = Math.max(...values); const span = Math.max(max - min, 1);
  const path = (field: "botEquityJpy" | "buyAndHoldEquityJpy" | "noTradeEquityJpy") => points.map((point, index) => `${(index / (points.length - 1)) * 100},${60 - ((Number(point[field]) - min) / span) * 50}`).join(" ");
  return <><svg viewBox="0 0 100 65" role="img" aria-labelledby="equity-chart-title equity-chart-desc"><title id="equity-chart-title">Bot realized equity and benchmark lines</title><desc id="equity-chart-desc">JPY equity over {points.length} daily observations. Blue is bot realized, amber is buy and hold, gray is no trade.</desc><polyline points={path("botEquityJpy")} fill="none" stroke="currentColor" strokeWidth="1.5" /><polyline points={path("buyAndHoldEquityJpy")} fill="none" stroke="#c78b2c" strokeWidth="1.2" /><polyline points={path("noTradeEquityJpy")} fill="none" stroke="#777" strokeWidth="1" /></svg><p>Legend: bot realized (blue/current color), buy &amp; hold (amber), no trade (gray). Unit: JPY.</p></>;
}

function humanStatus(status: string): string {
  return status.toLowerCase().replaceAll("_", " ");
}
