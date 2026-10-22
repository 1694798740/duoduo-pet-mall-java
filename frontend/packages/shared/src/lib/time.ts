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
