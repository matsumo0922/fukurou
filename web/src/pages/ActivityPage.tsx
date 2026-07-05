import { Activity } from "lucide-react";
import { EmptyState } from "../ui/components/EmptyState";
import { Panel } from "../ui/components/Panel";
import { SectionHeader } from "../ui/components/SectionHeader";

export function ActivityPage() {
  return (
    <div className="page-stack">
      <SectionHeader
        eyebrow="App"
        title="Activity"
        description="Execution and audit timeline."
      />

      <Panel>
        <div className="panel-heading">
          <Activity size={18} aria-hidden="true" />
          <h2>Timeline</h2>
        </div>
        <EmptyState
          title="No activity records loaded"
          description="Execution and audit records are not available from this route yet."
        />
      </Panel>
    </div>
  );
}
