import { describe, expect, it } from "vitest";
import {
  newestFirstActivityTimelineEvents,
  type ActivityTimelineEvent,
  type ActivityTimelineSource,
} from "./ops";

describe("ops activity timeline helpers", () => {
  it("sorts same-timestamp events with the backend source and id tie-break", () => {
    const events = [
      activityEvent("execution:z", "execution", "2026-07-05T12:00:00.000Z"),
      activityEvent("audit:b", "audit", "2026-07-05T12:00:00.000Z"),
      activityEvent("decision:a", "decision", "2026-07-05T12:00:00.000Z"),
      activityEvent("audit:a", "audit", "2026-07-05T12:00:00.000Z"),
      activityEvent("decision:new", "decision", "2026-07-05T12:00:01.000Z"),
    ];

    expect(newestFirstActivityTimelineEvents(events).map((event) => event.id)).toEqual([
      "decision:new",
      "audit:a",
      "audit:b",
      "decision:a",
      "execution:z",
    ]);
  });
});

function activityEvent(
  id: string,
  source: ActivityTimelineSource,
  occurredAt: string,
): ActivityTimelineEvent {
  return {
    id,
    source,
    kind: "TEST",
    title: "TEST",
    detail: "test detail",
    occurredAt,
    metadata: [],
  };
}
