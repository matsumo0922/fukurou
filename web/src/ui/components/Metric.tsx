type MetricProps = {
  label: string;
  value: string;
  detail?: string;
};

export function Metric({ label, value, detail }: MetricProps) {
  return (
    <div className="metric">
      <div className="metric__label-row">
        <p className="metric__label">{label}</p>
      </div>
      <p className="metric__value">{value}</p>
      {detail ? <p className="metric__detail">{detail}</p> : null}
    </div>
  );
}
