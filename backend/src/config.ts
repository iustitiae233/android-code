import "dotenv/config";
import type { PermissionMode } from "./protocol.js";

const VALID_MODES: PermissionMode[] = [
  "default",
  "acceptEdits",
  "plan",
  "dontAsk",
  "bypassPermissions",
];

function required(name: string): string {
  const v = process.env[name];
  if (!v) {
    console.error(`✗ 缺少必需的环境变量 ${name}（参考 .env.example）`);
    process.exit(1);
  }
  return v;
}

const rawMode = (process.env.PERMISSION_MODE ?? "default") as PermissionMode;
if (!VALID_MODES.includes(rawMode)) {
  console.error(`✗ PERMISSION_MODE 无效: ${rawMode}，可选: ${VALID_MODES.join(", ")}`);
  process.exit(1);
}

export const config = {
  port: Number(process.env.PORT ?? 8787),
  authToken: required("AUTH_TOKEN"),
  defaultCwd: process.env.DEFAULT_CWD ?? process.cwd(),
  model: process.env.MODEL || undefined,
  defaultPermissionMode: rawMode,
};
