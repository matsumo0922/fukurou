import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { messages } from "../i18n/messages";
import {
  newestFirstActivityTimelineEvents,
  type ActivityTimelineEvent,
  type ActivityTimelineSource,
} from "./ops";

type CatalogGoldenItem = {
  value: string;
  labelKey: string;
  descriptionKey: string;
};

type CatalogGolden = {
  sourceFilters: CatalogGoldenItem[];
  auditEventTypes: CatalogGoldenItem[];
  decisionActions: CatalogGoldenItem[];
};

const activityCatalogGolden = JSON.parse(
  readFileSync(resolve(process.cwd(), "../testdata/ops-activity-catalog.golden.json"), "utf8"),
) as CatalogGolden;

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

  it("covers backend activity catalog i18n keys in both locales", () => {
    const enMessages = messages.en as Record<string, string | undefined>;
    const jaMessages = messages.ja as Record<string, string | undefined>;
    const catalogKeys = [
      ...activityCatalogGolden.sourceFilters,
      ...activityCatalogGolden.auditEventTypes,
      ...activityCatalogGolden.decisionActions,
    ].flatMap((item) => [item.labelKey, item.descriptionKey]);

    catalogKeys.forEach((key) => {
      expect(enMessages[key]).toBeTypeOf("string");
      expect(jaMessages[key]).toBeTypeOf("string");
    });
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
