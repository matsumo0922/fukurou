export type StatusTone = "positive" | "warning" | "critical" | "neutral" | "loading";

type StatusPillProps = {
  label: string;
  tone: StatusTone;
};

export function StatusPill({ label, tone }: StatusPillProps) {
  return <span className={`status-pill status-pill--${tone}`}>{label}</span>;
}
