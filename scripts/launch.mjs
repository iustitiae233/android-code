// Claude Remote 一键启动器
// 同时拉起后端(npm run dev) + Cloudflare 隧道，单窗口运行，Ctrl-C 一起退出。
// - 检测到 ~/.cloudflared/config.yml   → 走「命名隧道(固定域名)」
// - 否则                                → 走「快速隧道(域名每次变)」，并自动把 wss 地址复制到剪贴板
// 双击 start.bat 即可，或： node scripts/launch.mjs
import { spawn } from "node:child_process";
import { existsSync, readFileSync } from "node:fs";
import { homedir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));
const BACKEND_DIR = join(HERE, "..", "backend");
const CF_DIR = join(homedir(), ".cloudflared");
const CFG = ["config.yml", "config.yaml"].map((f) => join(CF_DIR, f)).find(existsSync);
const NAMED = Boolean(CFG);

// 从 backend/.env 读 token，避免硬编码
function readToken() {
  try {
    const env = readFileSync(join(BACKEND_DIR, ".env"), "utf8");
    const m = env.match(/^AUTH_TOKEN=(.*)$/m);
    return m ? m[1].trim() : "(见 backend/.env)";
  } catch {
    return "(见 backend/.env)";
  }
}
const TOKEN = readToken();

const procs = [];
const killAll = () => {
  for (const p of procs) {
    try {
      // Windows 下 kill 默认不杀子进程树，用 taskkill 兜底
      if (p.pid) spawn("taskkill", ["/PID", String(p.pid), "/T", "/F"]);
    } catch {}
  }
  process.exit(0);
};
process.on("SIGINT", killAll);
process.on("SIGTERM", killAll);

console.log(
  NAMED
    ? "\n[模式] 命名隧道（固定域名，手机不用再改）"
    : "\n[模式] 快速隧道（域名每次重启会变，下方会自动复制 wss 地址到剪贴板）"
);
console.log("[后端目录]", BACKEND_DIR);
console.log("[token]   ", TOKEN, "\n");

// ---------- 后端 ----------
const be = spawn("npm", ["run", "dev"], { cwd: BACKEND_DIR, shell: true });
be.stdout.on("data", (b) => process.stdout.write(`[backend] ${b}`));
be.stderr.on("data", (b) => process.stdout.write(`[backend] ${b}`));
be.on("error", (e) =>
  console.error("\n[backend] 启动失败，确认已装 Node.js 和 backend 依赖:", e.message)
);
be.on("exit", (c) => console.log(`\n[backend] 退出 code=${c}`));
procs.push(be);

// ---------- 隧道 ----------
const args = NAMED ? ["tunnel", "run"] : ["tunnel", "--url", "http://localhost:8787"];
const tu = spawn("cloudflared", args, { shell: true });
let urlDone = false;
tu.stdout.on("data", (b) => {
  const s = b.toString();
  process.stdout.write(`[tunnel] ${s}`);
  if (!NAMED && !urlDone) {
    const m = s.match(/https:\/\/[a-z0-9-]+\.trycloudflare\.com/i);
    if (m) {
      urlDone = true;
      const wss = m[0].replace(/^https:\/\//, "wss://");
      console.log("\n========================================");
      console.log(" 本次隧道域名（手机 Server URL 填这个）：");
      console.log("   " + wss);
      console.log("   Auth token：" + TOKEN);
      console.log(" （wss 地址已复制到剪贴板，手机上直接粘贴）");
      console.log("========================================\n");
      try {
        const cp = spawn("clip");
        cp.stdin.end(wss);
      } catch {}
    }
  }
});
tu.stderr.on("data", (b) => process.stdout.write(`[tunnel] ${b}`));
tu.on("error", (e) => {
  if (e.code === "ENOENT")
    console.error("\n[tunnel] 找不到 cloudflared，先装： winget install --id Cloudflare.cloudflared");
  else console.error("\n[tunnel] 启动失败:", e.message);
});
tu.on("exit", (c) => console.log(`\n[tunnel] 退出 code=${c}`));
procs.push(tu);
