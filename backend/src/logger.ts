// 极简结构化日志：受 LOG_LEVEL 环境变量控制（debug|info|warn|error，默认 info）。
// 不引第三方依赖，便于远程排障。

type Level = "debug" | "info" | "warn" | "error";
const ORDER: Record<Level, number> = { debug: 10, info: 20, warn: 30, error: 40 };

function currentLevel(): Level {
  const raw = (process.env.LOG_LEVEL ?? "info").toLowerCase();
  return raw in ORDER ? (raw as Level) : "info";
}

function emit(level: Level, tag: string, msg: string, extra?: unknown): void {
  if (ORDER[level] < ORDER[currentLevel()]) return;
  const line = `${new Date().toISOString()} [${level.toUpperCase()}] ${tag}: ${msg}`;
  const sink = level === "error" || level === "warn" ? console.error : console.log;
  if (extra !== undefined && currentLevel() === "debug") sink(line, extra);
  else sink(line);
}

export const log = {
  debug: (tag: string, msg: string, extra?: unknown): void => emit("debug", tag, msg, extra),
  info: (tag: string, msg: string, extra?: unknown): void => emit("info", tag, msg, extra),
  warn: (tag: string, msg: string, extra?: unknown): void => emit("warn", tag, msg, extra),
  error: (tag: string, msg: string, extra?: unknown): void => emit("error", tag, msg, extra),
};
