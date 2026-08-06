import { describe, it, expect, beforeAll } from "vitest";

// config.ts 在导入时会校验 AUTH_TOKEN 并可能 process.exit，所以先 stub 环境再动态导入。
let parseModes: (raw: string | undefined) => string[];

beforeAll(async () => {
  process.env.AUTH_TOKEN = "test-token";
  ({ parseModes } = await import("./config.js"));
});

describe("parseModes", () => {
  it("returns all modes when undefined / blank", () => {
    expect(parseModes(undefined)).toHaveLength(5);
    expect(parseModes("   ")).toHaveLength(5);
  });

  it("parses a subset", () => {
    expect(parseModes("default, acceptEdits")).toEqual(["default", "acceptEdits"]);
  });

  it("drops invalid entries but keeps valid ones", () => {
    expect(parseModes("default, bogus, plan")).toEqual(["default", "plan"]);
  });

  it("falls back to all modes when everything is invalid", () => {
    expect(parseModes("bogus, nope")).toHaveLength(5);
  });
});
