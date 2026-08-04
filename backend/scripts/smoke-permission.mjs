// 审批闭环冒烟测试：发一个会触发工具的 prompt → 收到 permission_request → 自动 allow → 验证工具执行 + result。
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

  const counts = {};
  got.forEach((m) => {
    counts[m.type] = (counts[m.type] || 0) + 1;
  });
  console.log("\n=== 消息类型统计 ===");
  console.log(counts);

  const reqs = got.filter((m) => m.type === "permission_request");
  console.log(`\n=== permission_request（${reqs.length} 条）===`);
  reqs.forEach((m) => console.log(`  • ${m.toolName} — ${m.summary}`));

  const tools = got.filter((m) => m.type === "tool_use");
  console.log(`\n=== tool_use（${tools.length} 条）===`);
  tools.forEach((m) => console.log(`  • ${m.toolName} input=${JSON.stringify(m.input).slice(0, 120)}`));

  const results = got.filter((m) => m.type === "tool_result");
  console.log(`\n=== tool_result（${results.length} 条，截断展示）===`);
  results.forEach((m) => console.log(`  • isError=${m.isError} content=${JSON.stringify(m.content).slice(0, 200)}`));

  const res = got.filter((m) => m.type === "result")[0];
  if (res) console.log(`\n=== result: subtype=${res.subtype} cost=$${res.costUsd?.toFixed(4)} ===`);

  try { ws?.close(); } catch {}
  process.exit(0);
}

function tryConnect(attempt) {
  ws = new WebSocket(URL);
  ws.on("open", () => {
    connected = true;
    console.log("[smoke-perm] 已连接");
    ws.send(JSON.stringify({ type: "auth", token: TOKEN }));
    setTimeout(() => {
      ws.send(
        JSON.stringify({
          type: "send_message",
          prompt: "请用 Write 工具在当前工作目录创建文件 perm-test.txt，内容为 approval works。必须实际调用 Write 工具写入文件。",
          permissionMode: "plan",
        }),
      );
    }, 800);
  });
  ws.on("message", (raw) => {
    let m;
    try { m = JSON.parse(raw.toString()); } catch { return; }
    got.push(m);
    // 收到审批请求 → 自动 allow（模拟手机用户点「允许」）
    if (m.type === "permission_request") {
      console.log(`[smoke-perm] 收到审批 → 自动 allow: ${m.toolName}`);
      ws.send(
        JSON.stringify({ type: "permission_response", requestId: m.requestId, decision: "allow" }),
      );
    }
    if (m.type === "result" || m.type === "error") finish();
  });
  ws.on("error", (e) => {
    if (!connected && attempt < 20) setTimeout(() => tryConnect(attempt + 1), 700);
    else { console.error("连接错误:", e.message); process.exit(1); }
  });
}

const hardTimeout = setTimeout(finish, 60000);
tryConnect(1);
