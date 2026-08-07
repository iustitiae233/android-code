# Claude Remote

手机（Android）远程控制 PC 上的 Claude Code，写代码或聊天。

```
┌──────────────┐   WebSocket (wss, JSON)    ┌─────────────────┐   Agent SDK   ┌──────────────┐
│  Android App │  ←──────────────────────→   │  Node.js 后端    │  ←─────────→  │  Claude Code │
│  Kotlin+     │   over Cloudflare Tunnel    │  (ws + SDK)     │               │  (GLM/Claude)│
│  Compose     │                             │                 │               │              │
└──────────────┘                             └─────────────────┘               └──────────────┘
```

- **`backend/`** — Node.js + TypeScript + [`@anthropic-ai/claude-agent-sdk`](https://code.claude.com/docs/en/agent-sdk/typescript)。WebSocket server，把 Claude Code 的流式输出（文本 / 工具调用 / 思考进度 / 成本）转发给手机，并桥接工具调用的权限审批。
- **`android/`** — Kotlin + Jetpack Compose。手机客户端。

## 快速开始

### 1. 后端

```bash
cd backend
copy .env.example .env          # Windows；macOS/Linux: cp .env.example .env
# 编辑 .env：把 AUTH_TOKEN 改成你自己的长随机串
npm install
npm run dev                     # 启动，监听 ws://localhost:8787
```

凭证：SDK 默认复用本机 `~/.claude` 凭证（你用 `claude` 登录的那个）。本机当前用的是**智谱 GLM-5.2 兼容 API**（见 `~/.claude/settings.json` 的 `ANTHROPIC_BASE_URL`），SDK 会自动使用，无需额外配置。若想用原生 Anthropic，改那两个 env 即可。

冒烟测试（不依赖手机）：

```bash
npm i -g wscat
wscat -c ws://localhost:8787
{"type":"auth","token":"<你的AUTH_TOKEN>"}
{"type":"send_message","prompt":"用一句话介绍你自己"}
```

### 2. 外网穿透（仅外网访问需要）

```bash
winget install --id Cloudflare.cloudflared      # 或 brew install cloudflared
cloudflared tunnel --url http://localhost:8787
# 输出 https://<random>.trycloudflare.com → 手机端填 wss://<random>.trycloudflare.com
```

局域网同 WiFi 则直接用 `ws://电脑IP:8787`，无需穿透。

### 3. 手机端

用 **Android Studio** 打开 `android/` 目录 → Run ▶ → 首次进入设置页填地址 + token → 保存即连接。详见 [`android/README.md`](android/README.md)。

或命令行出 APK（需 JDK 17）：

```bash
cd android && ./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

## 关于「工具审批弹窗」

后端用 `canUseTool` 实现了完整的异步审批机制（手机弹窗 → 用户允许/拒绝）。但**智谱 GLM 兼容端点会自动放行所有工具**，审批不触发（实测确认）。当前 MVP 因此聚焦：

- 流式聊天
- 工具调用可见（看 AI 在干什么）
- 会话续接（基于 sessionId resume）
- 中断 / 成本 / 思考进度
- 后台完成通知（手机端：任务在后台跑完时弹通知）

如需每个工具都弹窗审批，切到原生 Anthropic 端点即可，代码无需改动。

## 安全模型

这个工具能让手机远程驱动 PC 上的 Claude Code（读写文件、跑命令），所以默认加了几道闸：

- **明文提示（手机端）**：`ws://` 指向公网域名时，客户端**照常连接**，只在连接条提示一句「token 未加密，建议 wss://」——不硬拦，方便你连自建明文后端。只有地址格式错误才直接拒绝。能用 `wss://`（如 cloudflare 隧道）就尽量用，避免 token 明文走公网。
- **鉴权失败即断**：token 错时后端以 `1008` 关闭连接，客户端据此**停止重连**（不会再拿错误 token 无限重试）。
- **权限模式白名单（后端）**：`ALLOWED_PERMISSION_MODES` env 限定手机端可远程切换的模式。公网暴露时建议收紧成 `default,acceptEdits,plan`，这样即便 token 泄露，攻击者也无法把后端切到 `bypassPermissions` 任意执行。
- **并发守卫**：同一连接上一轮未结束就发新消息会被后端拒绝（返回 `busy`），避免流互相覆盖产生僵尸查询。
- **审批队列**：一轮里多个工具需要审批时，手机端按队列依次弹窗（不再只显示最后一个）。

## 开发与测试

后端用 vitest 覆盖鉴权、权限审批桥、配置解析等纯逻辑：

```bash
cd backend
npm test            # vitest run
npm run build       # tsc 类型检查 + 产出 dist/
npm run dev         # tsx watch 热重载
```

### 版本号（Android）

`versionCode` / `versionName` 由 git 提交数自动生成（`app/build.gradle.kts` 读 `git rev-list --count HEAD`）：每多一个提交版本号就往上走，安装时自动当作升级。所以**改完东西先 commit 再构建**，版本才会更新。

## 项目结构

```
remote/
├─ backend/                Node + TS + Agent SDK
│  ├─ src/
│  │  ├─ protocol.ts        消息协议（与 android Protocol.kt 对应）
│  │  ├─ claude-session.ts  ★ query() 封装 + canUseTool 审批桥
│  │  ├─ server.ts          WebSocket server
│  │  ├─ config.ts / auth.ts
│  │  └─ index.ts
│  ├─ scripts/              wscat 冒烟测试脚本
│  └─ .env.example
└─ android/                Kotlin + Compose
   └─ app/src/main/java/com/example/clauderemote/
      ├─ data/              Protocol.kt / WsClient.kt / ChatViewModel.kt / SettingsStore.kt
      └─ ui/                ChatScreen / MessageList / Composer / SettingsScreen / theme/
```
