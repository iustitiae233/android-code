import { query, type PermissionResult } from "@anthropic-ai/claude-agent-sdk";
import type { ServerMessage, PermissionMode } from "./protocol.js";
import { PermissionBridge } from "./permission-bridge.js";
import { log } from "./logger.js";

type SendFn = (msg: ServerMessage) => void;

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
  private bridge: PermissionBridge;
  private currentQuery: { interrupt?: () => void } | null = null;
  private currentMode: PermissionMode;
  private disposed = false;
  private currentCwd: string;

  constructor(
    private readonly send: SendFn,
    cwd: string,
    defaultMode: PermissionMode,
    private readonly model?: string,
    private readonly availableCwds: string[] = [],
  ) {
    this.currentMode = defaultMode;
    this.currentCwd = cwd;
    this.bridge = new PermissionBridge(send);
  }

  setPermissionMode(mode: PermissionMode): void {
    this.currentMode = mode;
  }

  /** 切换工作目录（调用前由 server 校验过白名单） */
  setCwd(cwd: string): void {
    this.currentCwd = cwd;
  }

  /** 当前工作目录（listSessions 等复用） */
  get cwd(): string {
    return this.currentCwd;
  }

  /** 当前是否正在跑一轮 agent（用于并发守卫） */
  get isBusy(): boolean {
    return this.currentQuery != null;
  }

  /** 手机端回审批结果，按 requestId 找到挂起的 Promise 并 resolve */
  resolvePermission(requestId: string, decision: "allow" | "deny", updatedInput?: unknown): void {
    this.bridge.resolve(requestId, decision, updatedInput);
  }

  /** canUseTool 回调：委托给 PermissionBridge */
  private canUseTool = async (
    toolName: string,
    input: unknown,
    options: { requestId?: string },
  ): Promise<PermissionResult> => this.bridge.request(toolName, input, options.requestId);

  /** 跑一轮 agent（send_message 触发）。同一连接禁止并发，避免 currentQuery 被覆盖产生僵尸流。 */
  async run(
    prompt: string,
    opts: { resume?: string; permissionMode?: PermissionMode } = {},
  ): Promise<void> {
    if (this.disposed) throw new Error("连接已关闭");
    if (this.currentQuery) throw new Error("上一轮仍在进行，请先点「中断」后再发送");

    const mode = opts.permissionMode ?? this.currentMode;
    const resumeTag = opts.resume ? ` (resume ${opts.resume.slice(0, 8)})` : "";
    log.info("query", `开始: mode=${mode}${resumeTag} prompt=${prompt.slice(0, 60)}`);

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
    this.currentQuery = stream as { interrupt?: () => void };

    try {
      for await (const msg of stream as AsyncIterable<any>) {
        this.forward(msg);
      }
    } catch (err: any) {
      log.error("query", `异常: ${err?.message ?? err}`);
      this.send({ type: "error", message: `query 异常: ${err?.message ?? err}`, code: "query_error" });
    } finally {
      this.currentQuery = null;
    }
  }

  /** 中断当前 turn */
  interrupt(): void {
    try {
      this.currentQuery?.interrupt?.();
      if (this.currentQuery) log.info("query", "用户中断");
    } catch {
      /* 忽略 */
    }
  }

  /** 连接关闭时清理：把所有挂起的审批拒绝，避免 query 卡死 */
  dispose(): void {
    this.disposed = true;
    this.bridge.denyAll();
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
            availableCwds: this.availableCwds,
          });
        }
        break;
      }

      case "assistant": {
        const content: any[] = m.message?.content ?? [];
        // tool_use 作为独立事件推送（携带 toolUseId，供 tool_result 匹配）；
        // assistant 消息只承载 text/thinking，避免客户端重复渲染工具调用。
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
        const visible = content.filter((b) => b?.type !== "tool_use");
        this.send({ type: "assistant", messageId: m.message?.id ?? "", content: visible });
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
        log.info(
          "query",
          `完成: ${m.subtype ?? "success"}${typeof m.total_cost_usd === "number" ? ` $${m.total_cost_usd.toFixed(4)}` : ""}`,
        );
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
