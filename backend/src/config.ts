import "dotenv/config";
import type { PermissionMode } from "./protocol.js";

const ALL_MODES: PermissionMode[] = [
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
if (!ALL_MODES.includes(rawMode)) {
  console.error(`✗ PERMISSION_MODE 无效: ${rawMode}，可选: ${ALL_MODES.join(", ")}`);
  process.exit(1);
}

/** 解析 ALLOWED_PERMISSION_MODES（逗号分隔）。留空=全部允许；写错则回退到全部允许并告警。 */
export function parseModes(raw: string | undefined): PermissionMode[] {
  if (!raw || !raw.trim()) return [...ALL_MODES];
  const list = raw
    .split(",")
    .map((s) => s.trim())
    .filter(Boolean) as PermissionMode[];
  const valid = list.filter((m) => ALL_MODES.includes(m));
  if (valid.length !== list.length) {
    console.warn(`⚠ ALLOWED_PERMISSION_MODES 含非法项，已忽略: ${list.filter((m) => !ALL_MODES.includes(m)).join(", ")}`);
  }
  return valid.length ? valid : [...ALL_MODES];
}

const resolvedCwd = process.env.DEFAULT_CWD ?? process.cwd();

/** 解析 PROJECT_DIRS（逗号或分号分隔），始终包含 defaultCwd，去重保序。 */
export function parseDirs(raw: string | undefined, fallback: string): string[] {
  const list = (raw ?? "")
    .split(/[,;]/)
    .map((s) => s.trim())
    .filter(Boolean);
  return [...new Set([...list, fallback])];
}

export const config = {
  port: Number(process.env.PORT ?? 8787),
  authToken: required("AUTH_TOKEN"),
  defaultCwd: resolvedCwd,
  model: process.env.MODEL || undefined,
  defaultPermissionMode: rawMode,
  /** 客户端可远程切换的权限模式集合；bypassPermissions/dontAsk 默认含其中，可用 env 收紧。 */
  allowedPermissionModes: parseModes(process.env.ALLOWED_PERMISSION_MODES),
  /** 手机端可切换的工作目录白名单（PROJECT_DIRS），始终含 defaultCwd。 */
  projectDirs: parseDirs(process.env.PROJECT_DIRS, resolvedCwd),
};

/** 校验某模式是否被服务端允许客户端切换/使用 */
export function isModeAllowed(mode: string): mode is PermissionMode {
  return (config.allowedPermissionModes as string[]).includes(mode);
}

/** 校验某工作目录是否在白名单内（手机端只能切到这些目录） */
export function isDirAllowed(dir: string): boolean {
  return (config.projectDirs as string[]).includes(dir);
}
