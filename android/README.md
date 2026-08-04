# Claude Remote — Android

手机端，Kotlin + Jetpack Compose（Material 3）。连接 `backend/` 的 WebSocket，远程聊天 / 控制 Claude Code 写代码。

## 打开 & 构建

1. 装 **Android Studio**（Hedgehog 2023.1 或更新，自带 JDK 17）。
2. **File → Open** 选择 `android/` 目录（不是整个仓库根目录）。首次会自动 Gradle Sync 并下载依赖（需联网）。
3. 仓库未带 `gradlew` 二进制（无法版本控制 jar）；用 Android Studio 打开会自动补全 wrapper，命令行可用本地 `gradle wrapper` 生成。
4. 连接 Android 手机（开启 USB 调试）或启动模拟器，点 **Run ▶**。

> minSdk 26（Android 8.0），compileSdk 34。

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
- 思考进度（thinking tokens 计数）
- 一键中断当前任务
- 每轮成本显示
- 深色主题（跟随系统）+ 动态取色（Android 12+）
- 断线自动指数退避重连

## 已知限制

后端若使用智谱 GLM 等兼容端点，工具会被自动放行，**不会出现「工具审批弹窗」**（审批代码已实现，原生 Claude 端点会触发）。当前 MVP 聚焦聊天 + 工具可见性。
