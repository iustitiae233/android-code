// 冒烟测试：连接后端 → auth → 发极简 prompt → 打印所有协议消息 → 收到 result/error 或超时退出。
// 用法：先 `npm run dev` 起服务，再 `node scripts/smoke-test.mjs`
import { WebSocket } from "ws";

const TOKEN = "smoke-test-token-12345";
const URL = "ws://localhost:8787";

const got = [];
let ws;
let connected = false;
let finished = false;

function finish() {
  if (finished) return;
  finished = true;
  clearTimeout(hardTimeout);
  console.log("\n=== 收到的消息 ===");
  got.forEach((x, i) => console.log(`[${i}] ${JSON.stringify(x).slice(0, 400)}`));
  console.log(`\n共 ${got.length} 条。`);
  try { ws?.close(); } catch {}
  process.exit(0);
}

function tryConnect(attempt) {
  ws = new WebSocket(URL);
  ws.on("open", () => {
    connected = true;
    console.log(`[smoke] 连接成功（第 ${attempt} 次尝试）`);
    ws.send(JSON.stringify({ type: "auth", token: TOKEN }));
    setTimeout(() => {
      ws.send(
        JSON.stringify({ type: "send_message", prompt: "只回复两个字：在的。不要使用任何工具。" }),
      );
    }, 800);
  });
  ws.on("message", (raw) => {
    let m;
    try { m = JSON.parse(raw.toString()); } catch { return; }
    got.push(m);
    if (m.type === "result" || m.type === "error") finish();
  });
  ws.on("error", (e) => {
    if (!connected && attempt < 20) {
      setTimeout(() => tryConnect(attempt + 1), 700);
    } else {
      console.error("[smoke] 连接错误:", e.message);
      process.exit(1);
    }
  });
}

const hardTimeout = setTimeout(finish, 30000);
tryConnect(1);
