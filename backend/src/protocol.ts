// 手机端 ↔ 后端 的 WebSocket 消息协议（JSON，每条消息都有 type 字段）
// 与 Android 端 data/Protocol.kt 保持一一对应。

export type PermissionMode =
  | "default"
  | "acceptEdits"
  | "plan"
  | "dontAsk"
  | "bypassPermissions";

// ────────────── 客户端 → 服务端 ──────────────
export type ClientMessage =
  | { type: "auth"; token: string }
  | { type: "send_message"; prompt: string; sessionId?: string; permissionMode?: PermissionMode }
  | { type: "interrupt" }
  | { type: "permission_response"; requestId: string; decision: "allow" | "deny"; updatedInput?: unknown }
  | { type: "list_sessions"; dir?: string }
  | { type: "load_session"; sessionId: string }
  | { type: "set_permission_mode"; mode: PermissionMode }
  | { type: "set_cwd"; cwd: string };

// ────────────── 服务端 → 客户端 ──────────────
export type ServerMessage =
  | { type: "hello"; ok: true; serverVersion: string }
  | { type: "error"; message: string; code?: string }
  | { type: "system"; sessionId: string; tools: string[]; model: string; cwd: string; availableCwds?: string[] }
  | { type: "assistant"; messageId: string; content: unknown[] }
  | { type: "assistant_partial"; textDelta: string }
  | { type: "thinking_progress"; estimatedTokens: number; delta: number }
  | { type: "tool_use"; toolUseId: string; toolName: string; input: unknown }
  | { type: "tool_result"; toolUseId: string; content: unknown; isError: boolean }
  | { type: "permission_request"; requestId: string; toolName: string; input: unknown; summary: string }
  | { type: "result"; subtype: string; costUsd?: number; usage?: unknown; terminalReason?: string; isError: boolean }
  | { type: "status"; message: string }
  | { type: "session_list"; sessions: unknown[] }
  | { type: "session_messages"; sessionId: string; messages: unknown[] };
