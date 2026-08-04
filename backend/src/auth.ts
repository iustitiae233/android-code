import { timingSafeEqual } from "node:crypto";

/** 用恒定时间比较校验 token，避免计时攻击 */
export function checkAuth(token: string | undefined, expected: string): boolean {
  if (!token || !expected) return false;
  const a = Buffer.from(token);
  const b = Buffer.from(expected);
  if (a.length !== b.length) return false;
  return timingSafeEqual(a, b);
}
