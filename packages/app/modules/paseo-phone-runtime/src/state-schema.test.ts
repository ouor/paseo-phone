import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { phoneRuntimeStateSchema } from "./state-schema";

// The fixtures are written by the Kotlin StateSnapshotTest; both sides must agree on them (architecture §7.1).
const fixturesDir = join(__dirname, "__fixtures__");
const fixtures = readdirSync(fixturesDir).filter((name) => /^state-.*\.json$/.test(name));

describe("phone runtime state fixtures", () => {
  it("has the representative states", () => {
    expect(fixtures.length).toBeGreaterThanOrEqual(6);
  });

  it.each(fixtures)("%s parses with the schema", (name) => {
    const value: unknown = JSON.parse(readFileSync(join(fixturesDir, name), "utf8"));
    const result = phoneRuntimeStateSchema.safeParse(value);
    expect(result.error).toBeUndefined();
  });

  it("rejects a snapshot with an unknown status", () => {
    expect(phoneRuntimeStateSchema.safeParse({ status: "installed" }).success).toBe(false);
  });
});
