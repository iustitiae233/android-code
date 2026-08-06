import type { PermissionResult } from "@anthropic-ai/claude-agent-sdk";
import type { ServerMessage } from "./protocol.js";

type SendFn = (msg: ServerMessage) => void;

interface Pending {
  resolve: (r: PermissionResult) => void;
  timer: NodeJS.Timeout;
}

const DEFAULT_TIMEOUT_MS = 5 * 60_000; // 5 分钟无响应自动拒绝

function randomId(): string {
  return Math.random().toString(36).slice(2) + Date.now().toString(36);
}

/** 生成人类可读的工具调用摘要，给手机弹窗展示 */
export function summarize(toolName: string, input: any): string {
  try {
    switch (toolName) {
      case "Bash":
        return `$ ${input?.command ?? ""}`;
      case "Write":
        return `写入文件: ${input?.file_path ?? ""}`;
      case "Edit":
        return `编辑文件: ${input?.file_path ?? ""}`;
      case "Read":
        return `读取文件: ${input?.file_path ?? ""}`;
      case "Glob":
        return `查找文件: ${input?.pattern ?? ""}`;
      case "Grep":
        return `搜索内容: ${input?.pattern ?? ""}`;
      case "Task":
      case "Agent":
        return `启动子 agent: ${input?.subagent_type ?? input?.description ?? ""}`;
      default: {
        const s = JSON.stringify(input);
        return `${toolName}: ${s.length > 120 ? s.slice(0, 120) + "…" : s}`;
      }
    }
  } catch {
    return toolName;
  }
}

/**
 * 把 canUseTool 回调桥接成「推给手机 → 等用户决策 → resolve」的异步审批。
 * 从 ClaudeConnection 抽出来以便单测；连接关闭时调用 denyAll() 防止 query 卡死。
 */
export class PermissionBridge {
  private pending = new Map<string, Pending>();

  constructor(
    private readonly send: SendFn,
    private readonly timeoutMs: number = DEFAULT_TIMEOUT_MS,
  ) {}

  /** 挂起一个 Promise，把请求推给客户端，等 resolve() / 超时 */
  request(toolName: string, input: unknown, requestId?: string): Promise<PermissionResult> {
    const id = requestId ?? randomId();
    this.send({
      type: "permission_request",
      requestId: id,
      toolName,
      input,
      summary: summarize(toolName, input),
    });
    return new Promise<PermissionResult>((resolve) => {
      const timer = setTimeout(() => {
        if (this.pending.has(id)) {
          this.pending.delete(id);
          resolve({ behavior: "deny", message: "审批超时自动拒绝" });
        }
      }, this.timeoutMs);
      this.pending.set(id, { resolve, timer });
    });
  }

  /** 客户端回审批结果，按 requestId 找到挂起的 Promise 并 resolve */
  resolve(requestId: string, decision: "allow" | "deny", updatedInput?: unknown): void {
    const p = this.pending.get(requestId);
    if (!p) return;
    clearTimeout(p.timer);
    this.pending.delete(requestId);
    if (decision === "allow")
      p.resolve({ behavior: "allow", updatedInput: updatedInput as Record<string, unknown> | undefined });
    else p.resolve({ behavior: "deny", message: "用户在手机端拒绝了此操作" });
  }

  /** 拒绝所有挂起（连接关闭时调用，避免 query 卡死） */
  denyAll(message = "连接已断开，自动拒绝"): void {
    for (const [, p] of this.pending) {
      clearTimeout(p.timer);
      p.resolve({ behavior: "deny", message });
    }
    this.pending.clear();
  }

  get size(): number {
    return this.pending.size;
  }
}
