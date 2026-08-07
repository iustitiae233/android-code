import { startServer } from "./server.js";

startServer();

// 致命错误必须让进程退出，而不是吞掉继续跑：
// - dev (tsx watch) 下，进程退出会被自动重启 —— 否则 startServer() 绑定端口失败
//   被这里吞掉后，会变成"进程还活着、但没监听任何端口"的僵尸，cloudflared 隧道因此 502。
// - 生产 (npm start) 下，请配合进程管理器（pm2 / systemd / Docker restart=always）使用，
//   让它崩了能被拉起。让进程带病续命远不如崩了重启。
process.on("unhandledRejection", (reason) => {
  console.error("✗ 未处理的 Promise 拒绝:", reason);
  process.exit(1);
});

process.on("uncaughtException", (err) => {
  console.error("✗ 未捕获的异常:", err);
  process.exit(1);
});
