import { query, type PermissionResult } from "@anthropic-ai/claude-agent-sdk";
import type { ServerMessage, PermissionMode } from "./protocol.js";

type SendFn = (msg: ServerMessage) => void;

const PERMISSION_TIMEOUT_MS = 5 * 60_000; // 5 分钟无响应自动拒绝

function randomId(): string {
  return Math.random().toString(36).slice(2) + Date.now().toString(36);
}

/** 生成人类可读的工具调用摘要，给手机弹窗展示 */
function summarize(toolName: string, input: any): string {
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

interface Pending {
  resolve: (r: PermissionResult) => void;
  timer: NodeJS.Timeout;
}

/**
 * 每条 WebSocket 连接持有一个 ClaudeConnection。
 * 把 Agent SDK 的 query() 流翻译成协议消息推给手机，
 * 并把 canUseTool 回调桥接成「推给手机 → 等用户决策 → resolve」的异步审批。
 *
 * 注意：canUseTool 的审批仅适用于前台主 turn；后台子 agent (Task/Workflow) 的审批
 * 会受 SDK 已知问题 (issue #384/#4775) 影响而提前关闭通道——故建议子 agent
 * 用 acceptEdits 或预设 allowedTools 自动放行，本类不依赖其审批。
 */
export class ClaudeConnection {
  private pending = new Map<string, Pending>();
  private currentQuery: any = null;
  private currentMode: PermissionMode;
  private disposed = false;

  constructor(
    private readonly send: SendFn,
    private readonly cwd: string,
    defaultMode: PermissionMode,
    private readonly model?: string,
  ) {
    this.currentMode = defaultMode;
  }

  setPermissionMode(mode: PermissionMode): void {
    this.currentMode = mode;
  }

  /** 手机端回审批结果，按 requestId 找到挂起的 Promise 并 resolve */
  resolvePermission(requestId: string, decision: "allow" | "deny", updatedInput?: unknown): void {
    const p = this.pending.get(requestId);
    if (!p) return;
    clearTimeout(p.timer);
    this.pending.delete(requestId);
    if (decision === "allow")
      p.resolve({ behavior: "allow", updatedInput: updatedInput as Record<string, unknown> | undefined });
    else p.resolve({ behavior: "deny", message: "用户在手机端拒绝了此操作" });
  }

  /** canUseTool 回调：挂起一个 Promise，把请求推给手机，等 permission_response */
  private canUseTool = async (
    toolName: string,
    input: unknown,
    options: { requestId?: string },
  ): Promise<PermissionResult> => {
    const requestId = options.requestId ?? randomId();
    this.send({
      type: "permission_request",
      requestId,
      toolName,
      input,
      summary: summarize(toolName, input),
    });

    return new Promise<PermissionResult>((resolve) => {
      const timer = setTimeout(() => {
        if (this.pending.has(requestId)) {
          this.pending.delete(requestId);
          resolve({ behavior: "deny", message: "审批超时（5 分钟）自动拒绝" });
        }
      }, PERMISSION_TIMEOUT_MS);
      this.pending.set(requestId, { resolve, timer });
    });
  };

  /** 跑一轮 agent（send_message 触发） */
  async run(
    prompt: string,
    opts: { resume?: string; permissionMode?: PermissionMode } = {},
  ): Promise<void> {
    if (this.disposed) throw new Error("连接已关闭");
    const mode = opts.permissionMode ?? this.currentMode;

    const queryOpts: Record<string, unknown> = {
      prompt,
      cwd: this.cwd,
      permissionMode: mode,
      includePartialMessages: true,
      canUseTool: this.canUseTool,
    };
    if (opts.resume) queryOpts.resume = opts.resume;
    if (this.model) queryOpts.model = this.model;

    const stream = query(queryOpts as any);
    this.currentQuery = stream;

    try {
      for await (const msg of stream as AsyncIterable<any>) {
        this.forward(msg);
      }
    } catch (err: any) {
      this.send({ type: "error", message: `query 异常: ${err?.message ?? err}`, code: "query_error" });
    } finally {
      this.currentQuery = null;
    }
  }

  /** 中断当前 turn */
  interrupt(): void {
    try {
      this.currentQuery?.interrupt?.();
    } catch {
      /* 忽略 */
    }
  }

  /** 连接关闭时清理：把所有挂起的审批拒绝，避免 query 卡死 */
  dispose(): void {
    this.disposed = true;
    for (const [, p] of this.pending) {
      clearTimeout(p.timer);
      p.resolve({ behavior: "deny", message: "连接已断开，自动拒绝" });
    }
    this.pending.clear();
    this.interrupt();
  }

  /** 把 SDKMessage 翻译成协议消息发给手机 */
  private forward(m: any): void {
    switch (m.type as string) {
      case "system": {
        // SDK 把 init 和 thinking_tokens 进度都标成 type:"system"，用 subtype 区分
        if (m.subtype === "thinking_tokens") {
          this.send({
            type: "thinking_progress",
            estimatedTokens: m.estimated_tokens ?? 0,
            delta: m.estimated_tokens_delta ?? 0,
          });
        } else if (m.tools || m.model) {
          this.send({
            type: "system",
            sessionId: m.session_id ?? "",
            tools: (m.tools ?? [])
              .map((t: any) => (typeof t === "string" ? t : t.name))
              .filter(Boolean),
            model: m.model ?? "",
            cwd: m.cwd ?? this.cwd,
          });
        }
        break;
      }

      case "assistant": {
        const content: any[] = m.message?.content ?? [];
        for (const block of content) {
          if (block?.type === "tool_use") {
            this.send({
              type: "tool_use",
              toolUseId: block.id,
              toolName: block.name,
              input: block.input,
            });
          }
        }
        this.send({ type: "assistant", messageId: m.message?.id ?? "", content });
        break;
      }

      case "user": {
        const content: any = m.content;
        if (Array.isArray(content)) {
          for (const block of content) {
            if (block?.type === "tool_result") {
              this.send({
                type: "tool_result",
                toolUseId: block.tool_use_id ?? "",
                content: block.content,
                isError: !!block.is_error,
              });
            }
          }
        }
        break;
      }

      case "assistant_partial": {
        const blocks: any[] = m.message?.content ?? [];
        for (const b of blocks) {
          if (b?.type === "text" && typeof b.text === "string") {
            this.send({ type: "assistant_partial", textDelta: b.text });
          }
        }
        break;
      }

      case "result":
        this.send({
          type: "result",
          subtype: m.subtype ?? "success",
          costUsd: typeof m.total_cost_usd === "number" ? m.total_cost_usd : undefined,
          usage: m.usage,
          terminalReason: m.terminal_reason,
          isError: !!m.is_error,
        });
        break;

      case "permission_denied":
        this.send({
          type: "status",
          message: `操作被自动拒绝: ${m.denial?.tool_name ?? ""}`,
        });
        break;

      case "status":
      case "tool_progress":
        // 心跳/状态消息可选展示，这里忽略以减少噪声
        break;

      default:
        // 未知消息类型静默忽略（向前兼容新版 SDK）
        break;
    }
  }
}
