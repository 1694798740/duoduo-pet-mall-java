/**
 * 时间显示（Web 统一口径，2026-10-21）。
 *
 * <h2>为什么必须有这一层</h2>
 * 后端所有时间按 **UTC** 存储与序列化（`...toInstant(UTC).toString()` → `2026-10-21T08:41:00Z`）。
 * 各页面此前用 `iso.replace('T',' ').slice(5,16)` 直接截字符串 ——
 * 于是 `Z` 被丢掉，08:41（UTC）当成 08:41（北京时间）显示，**整整早 8 小时**。
 * 这不是"某处写错"，而是"把 ISO 字符串当本地时间切片"这种写法必然出错：
 * 它既不认时区，也不认夏令时，还没有任何报错。
 *
 * <h2>口径</h2>
 * 平台面向国内用户，统一按 **Asia/Shanghai** 渲染（而不是跟随浏览器时区）：
 * 客服、订单、审核这类时间在团队内部要能对得上，跟着浏览器漂移会制造新的对不上。
 *
 * <p>带偏移量的 ISO（`Z` / `+08:00`）直接解析；不带时区的裸串按 UTC 处理 ——
 * 后端契约就是 UTC，宁可把它当 UTC，也不能让浏览器按本地时区猜。
 */

const BJ_TZ = 'Asia/Shanghai';

interface Parts {
  y: string;
  m: string;
  d: string;
  h: string;
  mi: string;
}

function parts(iso: string | null | undefined): Parts | null {
  if (!iso) return null;
  const hasZone = /([zZ]|[+-]\d{2}:?\d{2})$/.test(iso);
  const date = new Date(hasZone ? iso : `${iso}Z`);
  if (Number.isNaN(date.getTime())) return null;
  const fmt = new Intl.DateTimeFormat('en-CA', {
    timeZone: BJ_TZ,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  });
  const map: Record<string, string> = {};
  for (const p of fmt.formatToParts(date)) {
    if (p.type !== 'literal') map[p.type] = p.value;
  }
  return {
    y: map.year ?? '',
    m: map.month ?? '',
    d: map.day ?? '',
    h: map.hour === '24' ? '00' : map.hour ?? '',
    mi: map.minute ?? '',
  };
}

/** `10-21 16:41`（列表/气泡里的紧凑格式） */
export function formatBjShort(iso: string | null | undefined): string {
  const p = parts(iso);
  return p ? `${p.m}-${p.d} ${p.h}:${p.mi}` : '';
}

/** `2026-10-21 16:41`（详情/账号信息等需要年份的场景） */
export function formatBjDateTime(iso: string | null | undefined): string {
  const p = parts(iso);
  return p ? `${p.y}-${p.m}-${p.d} ${p.h}:${p.mi}` : '';
}

/** `2026-10-21` */
export function formatBjDate(iso: string | null | undefined): string {
  const p = parts(iso);
  return p ? `${p.y}-${p.m}-${p.d}` : '';
}

/** 足迹等按自然日分组时的组 key：北京时间下的 YYYY-MM-DD（不能拿 UTC 串 slice(0,10)） */
export function bjDateKey(iso: string): string {
  return formatBjDate(iso);
}

function bjDayKey(offsetDays: number): string {
  return formatBjDate(new Date(Date.now() - offsetDays * 86_400_000).toISOString());
}

/**
 * 足迹分组标题：今天 / 昨天 / M月D日（跨年带年份）。
 * 与淘宝同口径：相对称呼只给近两天，更早一律具体日期，不搞「3天前」。
 */
export function historyGroupLabel(iso: string): string {
  const p = parts(iso);
  if (!p) return '';
  const key = `${p.y}-${p.m}-${p.d}`;
  if (key === bjDayKey(0)) return '今天';
  if (key === bjDayKey(1)) return '昨天';
  const monthDay = `${Number(p.m)}月${Number(p.d)}日`;
  return p.y === bjDayKey(0).slice(0, 4) ? monthDay : `${p.y}年${monthDay}`;
}

// ---------------- 聊天时间分隔条（2026-10-25） ----------------

/** 分隔阈值：相邻两条消息间隔 ≥10 分钟才再显示时间 */
export const CHAT_GAP_MS = 10 * 60_000;

/** 聊天场景的时间文案：当天只显示 HH:mm；昨天/前天带相对称呼；更早「M月D日 HH:mm」 */
export function chatTimeLabel(iso: string | null | undefined): string {
  const p = parts(iso);
  if (!p) return '';
  const key = `${p.y}-${p.m}-${p.d}`;
  const hm = `${p.h}:${p.mi}`;
  if (key === bjDayKey(0)) return hm;
  if (key === bjDayKey(1)) return `昨天 ${hm}`;
  if (key === bjDayKey(2)) return `前天 ${hm}`;
  return `${Number(p.m)}月${Number(p.d)}日 ${hm}`;
}

/**
 * 会话内第 idx 条消息要不要插时间分隔条：
 * 第一条必插；与上一条间隔 ≥10 分钟插。返回 null = 不显示。
 */
export function chatSeparatorLabel(iso: string | null | undefined, prevIso?: string | null): string | null {
  if (!iso) return null;
  if (prevIso) {
    const gap = new Date(iso).getTime() - new Date(prevIso).getTime();
    if (Number.isFinite(gap) && gap < CHAT_GAP_MS) return null;
  }
  return chatTimeLabel(iso);
}
