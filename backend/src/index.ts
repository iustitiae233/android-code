import { startServer } from "./server.js";

startServer();

process.on("unhandledRejection", (reason) => {
  console.error("✗ 未处理的 Promise 拒绝:", reason);
});

process.on("uncaughtException", (err) => {
  console.error("✗ 未捕获的异常:", err);
});
