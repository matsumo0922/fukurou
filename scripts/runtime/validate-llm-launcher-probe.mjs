import { readFileSync } from "node:fs";

const [path, phase] = process.argv.slice(2);
if (!path || !["PROPOSER", "FALSIFIER"].includes(phase)) throw new Error("probe path and phase are required");
const probes = readFileSync(path, "utf8")
  .split("\n")
  .filter(Boolean)
  .map(line => JSON.parse(line))
  .filter(event => event.event === "launcher_probe");
if (probes.length !== 1) throw new Error(`expected exactly one launcher probe, got ${probes.length}`);
const probe = probes[0];
const expectedKeys = [
  "capAmb", "capBnd", "capEff", "capInh", "capPrm", "coreLimit", "dumpable", "env", "event",
  "gid", "groups", "launchFds", "liveFds", "noNewPrivs", "uid",
].sort();
if (JSON.stringify(Object.keys(probe).sort()) !== JSON.stringify(expectedKeys)) throw new Error("launcher probe fields mismatch");
const expectedEnvironment = [
  "FUKUROU_CANARY_LLM_CORE_LIMIT", "FUKUROU_CANARY_LLM_DUMPABLE", "FUKUROU_CANARY_LLM_LAUNCH_FDS",
  "FUKUROU_INVOCATION_ID", "FUKUROU_LLM_PROVIDER", "FUKUROU_MARKET_SNAPSHOT_ID", "FUKUROU_PROMPT_HASH",
  "FUKUROU_RUNTIME_CONFIG_HASH", "FUKUROU_RUNTIME_CONFIG_VERSION_ID", "FUKUROU_SYSTEM_PROMPT_VERSION",
  "HOME", "LANG", "LC_ALL", "PATH", "XDG_CACHE_HOME",
  ...(phase === "PROPOSER"
    ? ["CLAUDE_CONFIG_DIR"]
    : ["CODEX_HOME", "FUKUROU_CANARY_INTENT_ID", "FUKUROU_FALSIFIER_INTENT_ID"]),
].sort();
const expected = {
  uid: "10002\t10002\t10002\t10002",
  gid: "10004\t10004\t10004\t10004",
  groups: "",
  capInh: "0000000000000000",
  capPrm: "0000000000000000",
  capEff: "0000000000000000",
  capBnd: "00000000000001c5",
  capAmb: "0000000000000000",
  noNewPrivs: "0",
  dumpable: "0",
  coreLimit: "0:0",
  launchFds: "0,1,2",
  liveFds: ["0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15", "16", "17", "18", "19", "20", "21", "23"],
  env: expectedEnvironment,
};
for (const [field, value] of Object.entries(expected)) {
  if (JSON.stringify(probe[field]) !== JSON.stringify(value)) {
    throw new Error(`${field} mismatch: ${JSON.stringify(probe[field])}`);
  }
}
