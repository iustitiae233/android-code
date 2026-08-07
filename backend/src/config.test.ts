import { describe, it, expect, beforeAll } from "vitest";

// config.ts 在导入时会校验 AUTH_TOKEN 并可能 process.exit，所以先 stub 环境再动态导入。
let parseModes: (raw: string | undefined) => string[];
let parseDirs: (raw: string | undefined, fallback: string) => string[];

beforeAll(async () => {
  process.env.AUTH_TOKEN = "test-token";
  ({ parseModes, parseDirs } = await import("./config.js"));
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

describe("parseDirs", () => {
  it("always includes the fallback (default cwd) even when raw is blank", () => {
    expect(parseDirs(undefined, "C:\\proj")).toEqual(["C:\\proj"]);
    expect(parseDirs("  ", "C:\\proj")).toEqual(["C:\\proj"]);
  });

  it("parses comma- or semicolon-separated dirs and appends fallback", () => {
    expect(parseDirs("C:\\a,C:\\b", "C:\\def")).toEqual(["C:\\a", "C:\\b", "C:\\def"]);
    expect(parseDirs("C:\\a;C:\\b", "C:\\def")).toEqual(["C:\\a", "C:\\b", "C:\\def"]);
  });

  it("trims whitespace and drops empty entries", () => {
    expect(parseDirs("  C:\\a  , , C:\\b ", "C:\\def")).toEqual(["C:\\a", "C:\\b", "C:\\def"]);
  });

  it("dedupes, keeping first occurrence order", () => {
    expect(parseDirs("C:\\a,C:\\a,C:\\def", "C:\\def")).toEqual(["C:\\a", "C:\\def"]);
  });
});
