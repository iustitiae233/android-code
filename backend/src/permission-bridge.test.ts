import { describe, it, expect } from "vitest";
import { PermissionBridge, summarize } from "./permission-bridge.js";
import type { ServerMessage } from "./protocol.js";

describe("summarize", () => {
  it("formats Bash commands", () => {
    expect(summarize("Bash", { command: "ls -la" })).toBe("$ ls -la");
  });

  it("formats file paths for Write/Read/Edit", () => {
    expect(summarize("Write", { file_path: "/a/b.txt" })).toBe("写入文件: /a/b.txt");
    expect(summarize("Read", { file_path: "/a/b.txt" })).toBe("读取文件: /a/b.txt");
    expect(summarize("Edit", { file_path: "/a/b.txt" })).toBe("编辑文件: /a/b.txt");
  });

  it("formats search tools", () => {
    expect(summarize("Glob", { pattern: "*.kt" })).toBe("查找文件: *.kt");
    expect(summarize("Grep", { pattern: "TODO" })).toBe("搜索内容: TODO");
  });

  it("falls back to JSON for unknown tools", () => {
    expect(summarize("Custom", { a: 1 })).toBe('Custom: {"a":1}');
  });

  it("truncates long input", () => {
    const s = summarize("Custom", { x: "y".repeat(500) });
    expect(s.endsWith("…")).toBe(true);
    expect(s.length).toBeLessThan(140);
  });

  it("never throws on bad input", () => {
    expect(summarize("Bash", undefined)).toBe("$ ");
    const o: any = {};
    o.self = o; // default 分支会 JSON.stringify → 抛 → 被 catch 返回工具名
    expect(summarize("Custom", o)).toBe("Custom");
  });
});

describe("PermissionBridge", () => {
  function makeBridge(timeoutMs = 50) {
    const sent: ServerMessage[] = [];
    const send = (m: ServerMessage) => sent.push(m);
    const bridge = new PermissionBridge(send, timeoutMs);
    return { bridge, sent };
  }

  const firstId = (sent: ServerMessage[]) =>
    (sent[0] as Extract<ServerMessage, { type: "permission_request" }>).requestId;

  it("pushes a permission_request and stays pending", () => {
    const { bridge, sent } = makeBridge();
    void bridge.request("Bash", { command: "rm -rf /" });
    expect(sent).toHaveLength(1);
    expect(sent[0].type).toBe("permission_request");
    expect(bridge.size).toBe(1);
  });

  it("resolves allow", async () => {
    const { bridge, sent } = makeBridge();
    const p = bridge.request("Bash", { command: "ls" });
    bridge.resolve(firstId(sent), "allow");
    expect((await p).behavior).toBe("allow");
    expect(bridge.size).toBe(0);
  });

  it("resolves allow with updatedInput", async () => {
    const { bridge, sent } = makeBridge();
    const p = bridge.request("Bash", { command: "ls" });
    bridge.resolve(firstId(sent), "allow", { command: "ls -la" });
    const r = await p;
    expect(r.behavior).toBe("allow");
    expect((r as { updatedInput?: unknown }).updatedInput).toEqual({ command: "ls -la" });
  });

  it("resolves deny with a message", async () => {
    const { bridge, sent } = makeBridge();
    const p = bridge.request("Bash", { command: "ls" });
    bridge.resolve(firstId(sent), "deny");
    const r = await p;
    expect(r.behavior).toBe("deny");
    expect((r as { message?: string }).message).toBeTruthy();
  });

  it("auto-denies after timeout", async () => {
    const { bridge } = makeBridge(20);
    const r = await bridge.request("Bash", { command: "ls" });
    expect(r.behavior).toBe("deny");
    expect(bridge.size).toBe(0);
  });

  it("resolve for unknown id is a no-op", () => {
    const { bridge } = makeBridge();
    expect(() => bridge.resolve("nope", "allow")).not.toThrow();
  });

  it("denyAll rejects every pending request", async () => {
    const { bridge } = makeBridge(10_000);
    const p1 = bridge.request("Bash", { command: "a" });
    const p2 = bridge.request("Bash", { command: "b" });
    expect(bridge.size).toBe(2);
    bridge.denyAll();
    const [r1, r2] = await Promise.all([p1, p2]);
    expect(r1.behavior).toBe("deny");
    expect(r2.behavior).toBe("deny");
    expect(bridge.size).toBe(0);
  });
});
