import type { ReactNode } from "react";

type PanelProps = {
  children: ReactNode;
  className?: string;
};

export function Panel({ children, className }: PanelProps) {
  return <section className={["panel", className].filter(Boolean).join(" ")}>{children}</section>;
}
