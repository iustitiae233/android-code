# Claude Remote Backend

WebSocket 桥：让手机（Android app）远程控制本机的 Claude Code，基于 `@anthropic-ai/claude-agent-sdk`。

## 工作原理

```
手机 ──WebSocket(JSON)──▶ 本后端 ──Agent SDK──▶ Claude CLI 子进程
```

后端把 SDK 的流式消息翻译成 JSON 协议推给手机，并把工具调用的权限审批桥接成「手机弹窗 → 用户决策」。

## 准备

1. **Node.js 20+**
2. **Claude 凭证**：在本机先跑一次 `claude` 登录（订阅/OAuth），SDK 会自动复用 `~/.claude` 凭证；
   或设置环境变量 `ANTHROPIC_API_KEY`。
3. （Windows）确保 `claude` 在 PATH；若 SDK 找不到二进制，额外装
   `npm i @anthropic-ai/claude-agent-sdk-win32-x64`。

## 安装 & 运行

```bash
cd backend
cp .env.example .env          # Windows: copy .env.example .env
# 编辑 .env，把 AUTH_TOKEN 改成你自己的长随机串
npm install
npm run dev                   # 监听 ws://localhost:8787
```

## 冒烟测试（wscat）

```bash
npm i -g wscat
wscat -c ws://localhost:8787
# 连上后依次发：
{"type":"auth","token":"<你的 AUTH_TOKEN>"}
{"type":"send_message","prompt":"用一句话介绍你自己，并列出当前目录的文件"}
```

应看到：`hello` → `system` → `assistant_partial`（流式）→ `tool_use`/`tool_result` → `result`。

若 `PERMISSION_MODE=default`，工具调用前会收到 `permission_request`，回：

```json
{"type":"permission_response","requestId":"<上面的 requestId>","decision":"allow"}
```

## 外网访问（Cloudflare Tunnel）

```bash
winget install --id Cloudflare.cloudflared
cloudflared tunnel --url http://localhost:8787
# 输出 https://<random>.trycloudflare.com → 手机端填 wss://<random>.trycloudflare.com
```

建议叠加 Cloudflare Access 做第二层鉴权。

## 协议

见 `src/protocol.ts`（与 Android 端 `data/Protocol.kt` 一一对应）。
