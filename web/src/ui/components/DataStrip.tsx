import type { ReactNode } from "react";

export type DataStripItem = {
  label: string;
  value: ReactNode;
  detail?: ReactNode;
};

type DataStripProps = {
  items: DataStripItem[];
};

export function DataStrip({ items }: DataStripProps) {
  return (
    <dl className="data-strip">
      {items.map((item) => (
        <div className="data-strip__item" key={item.label}>
          <dt>{item.label}</dt>
          <dd>{item.value}</dd>
          {item.detail ? <dd className="data-strip__detail">{item.detail}</dd> : null}
        </div>
      ))}
    </dl>
  );
}
