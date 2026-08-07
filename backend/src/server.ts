import { WebSocketServer, WebSocket } from "ws";
import { listSessions, getSessionMessages } from "@anthropic-ai/claude-agent-sdk";
import { config, isModeAllowed, isDirAllowed } from "./config.js";
import { checkAuth } from "./auth.js";
import { ClaudeConnection } from "./claude-session.js";
import { log } from "./logger.js";
import type { ClientMessage, ServerMessage, PermissionMode } from "./protocol.js";

const SERVER_VERSION = "0.2.0";

/** 客户端要切换/使用某权限模式前的服务端校验，越权则发 error 并返回 false。 */
function enforceMode(ws: WebSocket, send: (m: ServerMessage) => void, mode: string): mode is PermissionMode {
  if (isModeAllowed(mode)) return true;
  send({
    type: "error",
    message: `权限模式 ${mode} 未被服务端允许（ALLOWED_PERMISSION_MODES=${config.allowedPermissionModes.join(",")}）`,
    code: "mode_not_allowed",
  });
  log.warn("perm", `拒绝越权模式: ${mode}`);
  return false;
}

export function startServer(): void {
  const wss = new WebSocketServer({ port: config.port });

  wss.on("connection", (ws, req) => {
    let conn: ClaudeConnection | null = null;
    let authed = false;
    const ip = req.socket.remoteAddress ?? "?";
    log.info("ws", `新连接 ${ip}`);

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
            config.projectDirs,
          );
          log.info("ws", `鉴权成功 ${ip}`);
          send({ type: "hello", ok: true, serverVersion: SERVER_VERSION });
        } else {
          log.warn("ws", `鉴权失败 ${ip}`);
          send({ type: "error", message: "未授权：token 无效", code: "unauthorized" });
          // 1008 (Policy Violation)：客户端据此停止重连
          ws.close(1008, "unauthorized");
        }
        return;
      }

      try {
        switch (msg.type) {
          case "send_message": {
            if (conn!.isBusy) {
              send({ type: "error", message: "上一轮仍在进行，请先中断后再发送", code: "busy" });
              return;
            }
            let mode: PermissionMode | undefined;
            if (msg.permissionMode && !enforceMode(ws, send, msg.permissionMode)) return;
            mode = msg.permissionMode;
            await conn!.run(msg.prompt, { resume: msg.sessionId, permissionMode: mode });
            break;
          }

          case "permission_response":
            conn!.resolvePermission(msg.requestId, msg.decision, msg.updatedInput);
            break;

          case "interrupt":
            conn!.interrupt();
            break;

          case "set_permission_mode":
            if (!enforceMode(ws, send, msg.mode)) return;
            conn!.setPermissionMode(msg.mode);
            send({ type: "status", message: `权限模式已切换为 ${msg.mode}` });
            break;

          case "set_cwd":
            if (!isDirAllowed(msg.cwd)) {
              send({ type: "error", message: `工作目录不在白名单内: ${msg.cwd}`, code: "cwd_not_allowed" });
              return;
            }
            conn!.setCwd(msg.cwd);
            send({ type: "status", message: `工作目录已切换为 ${msg.cwd}` });
            break;

          case "list_sessions": {
            const sessions = await listSessions({ dir: msg.dir ?? conn!.cwd });
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

    ws.on("close", (code) => {
      log.info("ws", `连接关闭 ${ip} (${code})`);
      conn?.dispose();
    });

    ws.on("error", (err) => {
      log.warn("ws", `socket 错误 ${ip}: ${err.message}`);
    });
  });

  console.log(`✅ Claude Remote 后端已启动: ws://localhost:${config.port}`);
  console.log(`   工作目录 : ${config.defaultCwd}`);
  console.log(`   权限模式 : ${config.defaultPermissionMode}`);
  console.log(`   允许模式 : ${config.allowedPermissionModes.join(", ")}`);
  if (config.model) console.log(`   模型     : ${config.model}`);
  console.log(`   提示     : 公网暴露请用 cloudflared + wss://，并配合 Cloudflare Access`);
}
