# Claude Remote — Android

手机端，Kotlin + Jetpack Compose（Material 3）。连接 `backend/` 的 WebSocket，远程聊天 / 控制 Claude Code 写代码。

## 打开 & 构建

> 需要 **JDK 17**（Android Studio 自带；命令行构建把 `JAVA_HOME` 指向 JDK 17 即可）。minSdk 26（Android 8.0），compileSdk 34。

### 方式 A：Android Studio

1. 装 **Android Studio**（Hedgehog 2023.1 或更新，自带 JDK 17）。
2. **File → Open** 选择 `android/` 目录（不是整个仓库根目录）。首次会自动 Gradle Sync 并下载依赖（需联网）。
3. 连接 Android 手机（开启 USB 调试）或启动模拟器，点 **Run ▶**。

### 方式 B：命令行（Gradle Wrapper）

仓库已带 `gradlew` / `gradlew.bat` / `gradle-wrapper.jar`，无需额外装 Gradle：

```bash
export JAVA_HOME=/path/to/jdk17     # Windows cmd: set JAVA_HOME=C:\path\to\jdk17
./gradlew assembleDebug              # Windows 用 gradlew.bat
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

## 配置

首次打开 app 自动进入设置页，填：

- **WebSocket 地址**：
  - 局域网：`ws://电脑局域网IP:8787`（如 `ws://192.168.1.100:8787`）
  - 外网：`wss://你的 cloudflare 隧道域名`
- **AUTH_TOKEN**：与后端 `backend/.env` 的 `AUTH_TOKEN` 完全一致。

保存后自动连接，顶部状态栏显示连接状态。

## 功能

- 流式聊天（打字机效果，逐字显示）
- **工具调用可见**：实时看 AI 跑了什么命令 / 改了哪个文件（⏳ 进行中 → ✓ 完成）
- **工具调用折叠**：一轮里多次 Read/Bash 等会折叠成「执行了 N 个操作」，点开才展开，不刷屏
- **会话历史**：抽屉里列出历史会话，可点开载入之前的对话
- **工作目录切换**：抽屉头部显示当前目录，点按可从后端 `PROJECT_DIRS` 白名单里切换项目（切目录 = 开新会话）
- 思考进度（秒级计时 + thinking tokens 计数，不再像卡死）
- 一键中断当前任务
- 每轮成本显示 + **会话累计成本**（抽屉头部看本次会话累计 $ 与 token）
- **后台完成通知**：切到别的 app，一轮任务完成/出错时弹通知，点按回到对话
- **回到底部按钮**：长对话上滑后右下角一键回到底部
- 应用图标（自适应图标，品牌色 + Android 13+ 主题化）
- 版本号随 git 提交数自增
- 深色主题（跟随系统）+ 动态取色（Android 12+）
- 断线自动指数退避重连

## 已知限制

后端若使用智谱 GLM 等兼容端点，工具会被自动放行，**不会出现「工具审批弹窗」**（审批代码已实现，原生 Claude 端点会触发）。当前 MVP 聚焦聊天 + 工具可见性。
