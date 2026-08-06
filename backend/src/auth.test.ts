import { describe, it, expect } from "vitest";
import { checkAuth } from "./auth.js";

describe("checkAuth", () => {
  const expected = "s3cret-token-123";

  it("accepts the correct token", () => {
    expect(checkAuth(expected, expected)).toBe(true);
  });

  it("rejects a wrong token", () => {
    expect(checkAuth("wrong", expected)).toBe(false);
  });

  it("rejects empty / undefined token", () => {
    expect(checkAuth(undefined, expected)).toBe(false);
    expect(checkAuth("", expected)).toBe(false);
  });

  it("rejects when expected is empty", () => {
    expect(checkAuth("x", "")).toBe(false);
  });

  it("rejects prefix / suffix matches of different length", () => {
    expect(checkAuth("s3cret", expected)).toBe(false);
    expect(checkAuth("s3cret-token-123-extra", expected)).toBe(false);
  });
});
