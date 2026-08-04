import { WebSocketServer, WebSocket } from "ws";
import { listSessions, getSessionMessages } from "@anthropic-ai/claude-agent-sdk";
import { config } from "./config.js";
import { checkAuth } from "./auth.js";
import { ClaudeConnection } from "./claude-session.js";
import type { ClientMessage, ServerMessage } from "./protocol.js";

const SERVER_VERSION = "0.1.0";

export function startServer(): void {
  const wss = new WebSocketServer({ port: config.port });

  wss.on("connection", (ws) => {
    let conn: ClaudeConnection | null = null;
    let authed = false;

    const send = (msg: ServerMessage): void => {
      if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(msg));
    };

    ws.on("message", async (raw) => {
      let msg: ClientMessage;
      try {
        msg = JSON.parse(raw.toString());
      } catch {
        send({ type: "error", message: "非法 JSON", code: "bad_json" });
        return;
      }

      // 未鉴权时只接受 auth
      if (!authed) {
        if (msg.type === "auth" && checkAuth(msg.token, config.authToken)) {
          authed = true;
          conn = new ClaudeConnection(
            send,
            config.defaultCwd,
            config.defaultPermissionMode,
            config.model,
          );
          send({ type: "hello", ok: true, serverVersion: SERVER_VERSION });
        } else {
          send({ type: "error", message: "未授权：token 无效", code: "unauthorized" });
          ws.close();
        }
        return;
      }

      try {
        switch (msg.type) {
          case "send_message":
            await conn!.run(msg.prompt, {
              resume: msg.sessionId,
              permissionMode: msg.permissionMode,
            });
            break;

          case "permission_response":
            conn!.resolvePermission(msg.requestId, msg.decision, msg.updatedInput);
            break;

          case "interrupt":
            conn!.interrupt();
            break;

          case "set_permission_mode":
            conn!.setPermissionMode(msg.mode);
            send({ type: "status", message: `权限模式已切换为 ${msg.mode}` });
            break;

          case "list_sessions": {
            const sessions = await listSessions({ dir: msg.dir ?? config.defaultCwd });
            send({ type: "session_list", sessions: sessions as unknown[] });
            break;
          }

          case "load_session": {
            const messages = await getSessionMessages(msg.sessionId);
            send({
              type: "session_messages",
              sessionId: msg.sessionId,
              messages: messages as unknown[],
            });
            break;
          }

          default:
            send({
              type: "error",
              message: `未知消息类型: ${(msg as { type: string }).type}`,
            });
        }
      } catch (err: any) {
        send({ type: "error", message: err?.message ?? String(err), code: "handler_error" });
      }
    });

    ws.on("close", () => {
      conn?.dispose();
    });

    ws.on("error", () => {
      /* 连接错误静默处理，避免进程崩溃 */
    });
  });

  console.log(`✅ Claude Remote 后端已启动: ws://localhost:${config.port}`);
  console.log(`   工作目录 : ${config.defaultCwd}`);
  console.log(`   权限模式 : ${config.defaultPermissionMode}`);
  if (config.model) console.log(`   模型     : ${config.model}`);
  console.log(`   提示     : 公网暴露请用 cloudflared + wss://，并配合 Cloudflare Access`);
}
