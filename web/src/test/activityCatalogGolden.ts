import { existsSync, readFileSync } from "node:fs";
import { resolve } from "node:path";

const ACTIVITY_CATALOG_GOLDEN_PATHS = [
  resolve(process.cwd(), "../testdata/ops-activity-catalog.golden.json"),
  resolve(process.cwd(), "testdata/ops-activity-catalog.golden.json"),
];

export function readActivityCatalogGolden<T>(): T {
  const goldenPath = ACTIVITY_CATALOG_GOLDEN_PATHS.find((candidatePath) => existsSync(candidatePath));

  if (!goldenPath) {
    throw new Error(`Activity catalog golden not found: ${ACTIVITY_CATALOG_GOLDEN_PATHS.join(", ")}`);
  }

  return JSON.parse(readFileSync(goldenPath, "utf8")) as T;
}
