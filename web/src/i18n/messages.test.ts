import { describe, expect, it } from "vitest";
import { messages } from "./messages";

const reserveKeys = [
  "runner.entryFillReservePerHour",
  "runner.entryFillReservePerDay",
  "runner.stopProximityReservePerHour",
  "runner.stopProximityReservePerDay",
] as const;

describe("runtime reserve config messages", () => {
  it.each(["en", "ja"] as const)("defines labels and descriptions for every reserve key in %s", (locale) => {
    for (const key of reserveKeys) {
      expect(messages[locale][`config.item.${key}.label`]).toBeTruthy();
      expect(messages[locale][`config.item.${key}.description`]).toBeTruthy();
    }
  });
});
