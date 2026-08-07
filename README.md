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

### 前置条件

- **后端宿主（电脑）**：已装 [Claude Code](https://claude.ai/download) 并用 `claude` 登录过（Agent SDK 复用本机 `~/.claude` 凭证）；Node.js 18+（建议 20+）。
- **手机**：Android 8.0（API 26）及以上。
- **网络**三选一，决定手机端怎么填地址：同 WiFi 局域网（最简单）/ 公网加密（Cloudflare 隧道 `wss://`，推荐）/ 公网明文自建（自己的域名 `ws://`，token 不加密，仅自用）。

> Windows 用 `copy` / `cmd`，macOS/Linux 用 `cp` / 普通 shell，下文两种都给。

### 1. 配置并启动后端

```bash
cd backend
copy .env.example .env          # macOS/Linux: cp .env.example .env
npm install
```

编辑 `.env`（完整字段）：

| 变量 | 说明 | 示例 |
|---|---|---|
| `AUTH_TOKEN` | **必填**。手机端鉴权用，自己设长随机串，两边一致 | 见下方生成命令 |
| `PORT` | 监听端口 | `8787` |
| `DEFAULT_CWD` | Claude Code 工作目录（读写文件范围） | `C:\Users\you\projects` |
| `MODEL` | 模型，留空则用本机 `claude` 配置 | `claude-sonnet-4-5` |
| `PERMISSION_MODE` | 默认权限模式 | `default` |
| `ALLOWED_PERMISSION_MODES` | 手机端可远程切换的模式（逗号分隔），留空=全部 | `default,acceptEdits,plan` |
| `LOG_LEVEL` | 日志级别 `debug/info/warn/error` | `info` |

生成 `AUTH_TOKEN`（任选其一）：

```bash
node -e "console.log(require('crypto').randomBytes(24).toString('hex'))"   # 跨平台
openssl rand -hex 24                                                       # macOS/Linux
```

启动：

```bash
npm run dev        # 开发热重载；监听 ws://localhost:8787
# 生产：npm run build && npm start   （务必配 pm2 / systemd / Docker restart=always，见「常见问题」）
```

看到这几行即成功：

```
✅ Claude Remote 后端已启动: ws://localhost:8787
   工作目录 : ...
   权限模式 : default
```

**用哪个模型？** SDK 复用本机 `~/.claude` 凭证，无需在后端额外配 key：

- **原生 Anthropic**：本机 `claude` 登录过即可。
- **智谱 GLM 等兼容端点**：在 `~/.claude/settings.json` 设 `ANTHROPIC_BASE_URL` 与 `ANTHROPIC_AUTH_TOKEN`（即你 `claude` 本来在用的那套），SDK 自动走它，后端无感。

### 2. 验证后端（冒烟测试，不依赖手机）

```bash
npm i -g wscat
wscat -c ws://localhost:8787
{"type":"auth","token":"<你的AUTH_TOKEN>"}              # 应回 {"type":"hello","ok":true}
{"type":"send_message","prompt":"用一句话介绍你自己"}    # 开始流式返回
```

能收到流式回复 = 后端 + 模型凭证都正常。

### 3. 让手机连上（三种网络场景）

按你的场景选地址：

| 场景 | 手机端填 | 怎么得到 |
|---|---|---|
| 同 WiFi 局域网 | `ws://<电脑局域网IP>:8787` | Win `ipconfig` / Mac `ipconfig getifaddr en0` / Linux `hostname -I` |
| 公网加密（推荐） | `wss://<隧道域名>` | 见下方 Cloudflare 隧道 |
| 公网明文自建 | `ws://<你的域名>` | 你自己反代到 8787；token 明文，仅自用 |

公网加密（Cloudflare 隧道）：

```bash
winget install --id Cloudflare.cloudflared      # 或 brew install cloudflared
cloudflared tunnel --url http://localhost:8787
# 输出 https://<random>.trycloudflare.com → 手机端填 wss://<random>.trycloudflare.com
```

### 4. 安装并配置手机端

**出 APK**（需 JDK 17，Android Studio 自带；详见 [`android/README.md`](android/README.md)）：

```bash
cd android && ./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

**装到手机**（任选）：

```bash
adb install app/build/outputs/apk/debug/app-debug.apk   # 数据线 adb
# 或把 apk 传到手机，文件管理器点击安装（需开启「未知来源」）
```

或用 **Android Studio** 打开 `android/` 目录 → Run ▶ 直接装到手机调试。

**首次配置**：打开 app → 自动进设置页 → 填「WebSocket 地址」+「AUTH_TOKEN」（与后端 `.env` 完全一致）→ 保存 → 自动连接，顶部出现「已连接」→ 发条消息验证。

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

## 常见问题

- **连接错误：Token 无效** → 手机端 token 与后端 `AUTH_TOKEN` 不一致。后端以 `1008` 关闭并**停止重连**，改对 token 后重开 app。
- **连上了，发消息却报 `query 异常`** → 多半是模型凭证：本机没登录 `claude`，或 GLM 的 `ANTHROPIC_AUTH_TOKEN`/`ANTHROPIC_BASE_URL` 配错。先在电脑上把 `claude` 跑通再来。
- **`ws://` 连公网时顶部提示「token 未加密」** → 正常安全提示，不会拦截；能用 `wss://` 更好。
- **Cloudflare 隧道 502 / 后端没监听** → 后端崩了但进程没退（僵尸）。生产务必配进程管理器（pm2 / systemd / Docker `restart=always`）让它崩了自动拉起。
- **局域网连不上** → 电脑防火墙放行 `PORT`（默认 8787）；确认手机与电脑同一 WiFi。
- **「上一轮仍在进行，请先中断」** → 同一连接禁止并发，点输入框旁 ⏹ 中断当前轮再发。
- **装了新版没当成升级** → `versionCode` 由 git 提交数生成，**先 commit 再构建**版本号才会涨（见下文「版本号」）。
- **后台没收到完成通知** → Android 13+ 需授权通知（首次进聊天页会申请）；部分厂商系统需手动允许「通知」与「后台运行」。

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
