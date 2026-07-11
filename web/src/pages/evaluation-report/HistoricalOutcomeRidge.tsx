import { AxisBottom } from "@visx/axis";
import { Group } from "@visx/group";
import { scaleLinear } from "@visx/scale";
import { AreaClosed, LinePath } from "@visx/shape";
import { curveStepAfter } from "@visx/curve";
import { useMemo, useState } from "react";
import type { EvaluationReport, OutcomeRidgeGroup } from "../../api/evaluationReport";

export function HistoricalOutcomeRidge({ report }: { report: EvaluationReport }) {
  const [grouping, setGrouping] = useState("SETUP");
  const [mode, setMode] = useState<"SHARE" | "COUNT">("SHARE");
  const [showTable, setShowTable] = useState(false);
  const groups = report.outcomeRidge.groupings.find((item) => item.groupBy === grouping)?.groups ?? [];

  return (
    <section className="report-panel report-panel--wide" aria-labelledby="ridge-title">
      <header className="report-panel__header">
        <div><span className="console-kicker">HISTORICAL OUTCOME RIDGE</span><h2 id="ridge-title">Realized R distribution landscape</h2></div>
        <div className="console-badges"><span>OBSERVED · PAPER · NOT FORECAST</span><code>{report.snapshotId.slice(0, 12)}</code></div>
      </header>
      <div className="console-toolbar" aria-label="Ridge controls">
        {report.outcomeRidge.groupings.map((item) => <button className={grouping === item.groupBy ? "is-active" : ""} key={item.groupBy} onClick={() => setGrouping(item.groupBy)}>{item.groupBy.replace("MARKET_", "")}</button>)}
        <button className={mode === "SHARE" ? "is-active" : ""} onClick={() => setMode("SHARE")}>SHARE</button>
        <button className={mode === "COUNT" ? "is-active" : ""} onClick={() => setMode("COUNT")}>COUNT</button>
        <button onClick={() => setShowTable((value) => !value)}>TABLE</button>
      </div>
      {groups.length === 0 ? <p className="console-empty">RIDGE EMPTY · eligible observed outcomes are unavailable.</p> : <RidgeCanvas groups={groups} mode={mode} />}
      <p className="console-note">Fixed bins [-2R, +3R), width 0.25R. Missing R, exclusions and under/overflow are disclosed and never clamped.</p>
      {showTable && <RidgeTable groups={groups} />}
    </section>
  );
}

function RidgeCanvas({ groups, mode }: { groups: OutcomeRidgeGroup[]; mode: "SHARE" | "COUNT" }) {
  const width = 1100;
  const rowHeight = 84;
  const height = Math.max(170, groups.length * rowHeight + 58);
  const xScale = useMemo(() => scaleLinear({ domain: [-2, 3], range: [170, width - 30] }), []);
  const max = Math.max(1, ...groups.flatMap((group) => group.bins.map((bin) => mode === "COUNT" ? bin.count : bin.count / Math.max(1, group.availableRCount))));

  return <div className="ridge-scroll"><svg viewBox={`0 0 ${width} ${height}`} role="img" aria-label="Historical observed realized R distribution">
    <Group>
      {[-1, 0, 1].map((value) => <line key={value} x1={xScale(value)} x2={xScale(value)} y1={12} y2={height - 35} className={`ridge-reference ridge-reference--${value}`} />)}
      {groups.map((group, index) => {
        const baseline = 55 + index * rowHeight;
        const points = group.bins.flatMap((bin) => [{ x: Number(bin.lowerInclusive), y: mode === "COUNT" ? bin.count : bin.count / Math.max(1, group.availableRCount) }, { x: Number(bin.upperExclusive), y: mode === "COUNT" ? bin.count : bin.count / Math.max(1, group.availableRCount) }]);
        const yScale = scaleLinear({ domain: [0, max], range: [baseline, baseline - 46] });
        return <Group key={group.groupKey}>
          <text x={8} y={baseline - 15} className="ridge-label">{group.label}</text>
          <text x={8} y={baseline + 2} className="ridge-meta">n {group.availableRCount}/{group.tradeCount} · {group.sampleState}</text>
          {group.availableRCount > 0 && <><AreaClosed data={points} x={(point) => xScale(point.x)} y={(point) => yScale(point.y)} yScale={yScale} curve={curveStepAfter} fill="var(--console-ridge-fill)" /><LinePath data={points} x={(point) => xScale(point.x)} y={(point) => yScale(point.y)} curve={curveStepAfter} className="ridge-line" />{group.medianR != null && <rect x={xScale(Number(group.medianR)) - 4} y={baseline - 34} width={8} height={8} className="ridge-median" transform={`rotate(45 ${xScale(Number(group.medianR))} ${baseline - 30})`} />}</>}
        </Group>;
      })}
      <AxisBottom top={height - 35} scale={xScale} tickValues={[-2, -1, 0, 1, 2, 3]} tickFormat={(value) => `${Number(value) > 0 ? "+" : ""}${value}R`} />
    </Group>
  </svg></div>;
}

function RidgeTable({ groups }: { groups: OutcomeRidgeGroup[] }) {
  return <div className="console-table-wrap"><table><caption>Historical Outcome Ridge text representation</caption><thead><tr><th>Group</th><th>Available / total</th><th>Missing</th><th>Under / overflow</th><th>Median observed</th><th>Sample</th></tr></thead><tbody>{groups.map((group) => <tr key={group.groupKey}><th>{group.label}</th><td>{group.availableRCount} / {group.tradeCount}</td><td>{group.missingRCount}</td><td>{group.underflowCount} / {group.overflowCount}</td><td>{group.medianR ?? "missing"}</td><td>{group.sampleState}</td></tr>)}</tbody></table></div>;
}
