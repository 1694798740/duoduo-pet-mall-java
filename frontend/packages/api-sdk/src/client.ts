/**
 * 类型化 API 客户端（对接 Java 微服务网关）。
 *
 * 设计要点：
 *  · Base URL 走环境变量 VITE_API_BASE；留空即同源（开发态由 vite proxy 转发到网关）
 *  · 令牌通过 httpOnly Cookie（dd_at / dd_rt）自动携带 —— 前端拿不到也不需要拿，
 *    禁止写入 localStorage 或 URL 参数
 *  · 401 自动刷新令牌并重试原请求（单飞，避免并发 401 打出刷新风暴）
 *  · 4xx 一律不重试；5xx 与网络失败指数退避重试，最多 3 次
 *  · 把后端统一响应里的 code/message 解包成 ApiError，再经 toFriendlyMessage 映射为可读文案
 */

export const API_BASE: string = (import.meta.env.VITE_API_BASE as string | undefined) ?? '';

/** 后端统一响应包装（duoduo-common 的 ApiResponse） */
interface ApiEnvelope<T> {
  code: string;
  message: string;
  data: T;
  requestId?: string;
  timestamp?: number;
}

export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly requestId?: string;

  constructor(status: number, code: string, message: string, requestId?: string) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
    this.requestId = requestId;
  }

  get isAuthError(): boolean {
    return this.status === 401;
  }

  get isNetworkError(): boolean {
    return this.status === 0;
  }
}

export class OfflineError extends ApiError {
  constructor(message = '网络连接失败，请检查网络后重试') {
    super(0, 'OFFLINE', message);
    this.name = 'OfflineError';
  }
}

type Method = 'GET' | 'POST' | 'PATCH' | 'PUT' | 'DELETE';

/** 查询参数：接受任意对象；键值由 buildUrl 逐项过滤后序列化 */
export type QueryParams = object;

export interface RequestOptions {
  method?: Method;
  body?: unknown;
  query?: QueryParams;
  signal?: AbortSignal;
  /** 内部使用：是否允许 401 后刷新重试 */
  allowRefresh?: boolean;
  /** 内部使用：是否允许重试 */
  allowRetry?: boolean;
}

function buildUrl(path: string, query?: QueryParams): string {
  const base = path.startsWith('/') ? path : `/${path}`;
  if (!query) return `${API_BASE}${base}`;
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(query as Record<string, unknown>)) {
    // 只序列化标量；null/undefined/空串一律省略，避免下发脏参数
    if (value === undefined || value === null || value === '') continue;
    if (typeof value === 'object') continue;
    search.append(key, String(value));
  }
  const qs = search.toString();
  return `${API_BASE}${base}${qs ? `?${qs}` : ''}`;
}

/** 按 HTTP 状态码兜底的中文文案（后端未给出 message 时才用） */
function translateStatus(status: number): string {
  if (status === 400) return '请求参数有误';
  if (status === 401) return '登录已过期，请重新登录';
  if (status === 403) return '没有权限执行该操作';
  if (status === 404) return '请求的内容不存在';
  if (status === 409) return '操作冲突，请刷新后重试';
  if (status === 422) return '操作未通过校验，请检查填写内容';
  if (status === 429) return '操作过于频繁，请稍后再试';
  if (status >= 500) return '服务暂时不可用，请稍后重试';
  return '请求失败';
}

let refreshPromise: Promise<boolean> | null = null;

/**
 * 单飞刷新：并发的多个 401 只会触发一次刷新请求。
 *
 * <p>除 401 兜底外，还服务于「身份快照刚变化」的场景：实名认证 / 成为卖家都会把
 * `realNameVerified` / `role` 写进**访问令牌**（15 分钟内有效），而这两步成功后
 * 令牌不会自动更新 —— 不主动轮转一次，下一个请求仍带着旧快照，就会出现
 * 「个人中心显示已实名，发布闲置却 403」这类状态不一致（R52 在小程序端修过同款，
 * 网页端这次补齐；对应边界 B-08）。
 */
export function refreshSession(): Promise<boolean> {
  if (!refreshPromise) {
    refreshPromise = fetch(`${API_BASE}/api/auth/refresh`, {
      method: 'POST',
      credentials: 'include',
      headers: { 'content-type': 'application/json' },
    })
      .then((res) => res.ok)
      .catch(() => false)
      .finally(() => {
        // 留一个极小窗口，让同一批并发请求复用结果；之后允许再次刷新
        setTimeout(() => {
          refreshPromise = null;
        }, 50);
      });
  }
  return refreshPromise;
}

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

async function rawRequest<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { method = 'GET', body, query, signal } = options;
  // 文件上传走 multipart：**不能**手写 content-type —— 边界串由浏览器生成，
  // 手写会把 boundary 写错，服务端解析直接失败。同理不能 JSON.stringify 二进制。
  //
  // 刻意改在这一层而不是在页面里另起一个 fetch：401 单飞刷新、错误文案映射、
  // 重试策略都在这里，绕过去等于让上传成为唯一一个「令牌失效不续期」的请求。
  const isForm = typeof FormData !== 'undefined' && body instanceof FormData;
  let response: Response;
  try {
    response = await fetch(buildUrl(path, query), {
      method,
      credentials: 'include',
      signal,
      headers: body === undefined || isForm ? undefined : { 'content-type': 'application/json' },
      body: body === undefined ? undefined : (isForm ? body : JSON.stringify(body)),
    });
  } catch (error) {
    if ((error as Error).name === 'AbortError') throw error;
    throw new OfflineError();
  }

  if (response.status === 204) return undefined as T;

  const text = await response.text();
  let payload: Partial<ApiEnvelope<T>> = {};
  if (text) {
    try {
      payload = JSON.parse(text) as Partial<ApiEnvelope<T>>;
    } catch {
      // 非 JSON 响应（如网关直接返回的 HTML 错误页）：不进这里的正常路径
      if (!response.ok) {
        throw new ApiError(response.status, 'NON_JSON', translateStatus(response.status));
      }
      throw new ApiError(response.status, 'BAD_JSON', '服务返回了无法解析的数据');
    }
  }

  if (!response.ok) {
    throw new ApiError(
      response.status,
      payload.code ?? 'UNKNOWN',
      payload.message ?? translateStatus(response.status),
      payload.requestId,
    );
  }

  // 成功响应统一解包 data；data 为 null 时（如 logout）返回 undefined
  return (payload.data ?? undefined) as T;
}

export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const allowRefresh = options.allowRefresh ?? true;
  const allowRetry = options.allowRetry ?? true;
  const maxRetries = allowRetry ? 3 : 0;
  const isAuthEndpoint = path.startsWith('/api/auth/');

  let attempt = 0;
  for (;;) {
    try {
      return await rawRequest<T>(path, options);
    } catch (error) {
      if (error instanceof OfflineError) {
        // 网络类失败：指数退避重试
        if (attempt < maxRetries) {
          attempt += 1;
          await sleep(300 * attempt);
          continue;
        }
        throw error;
      }

      const apiError = error as ApiError;
      if (!(apiError instanceof ApiError)) throw error;

      // 401：刷新令牌后重试一次（认证端点自身不触发刷新，否则会递归）
      if (apiError.isAuthError && allowRefresh && !isAuthEndpoint) {
        const refreshed = await refreshSession();
        if (refreshed) {
          return rawRequest<T>(path, { ...options, allowRefresh: false });
        }
      }

      // 5xx 才重试；4xx 是确定性错误，重试没有意义
      if (apiError.status >= 500 && attempt < maxRetries) {
        attempt += 1;
        await sleep(400 * attempt);
        continue;
      }

      throw apiError;
    }
  }
}

export const api = {
  get: <T>(path: string, options: Omit<RequestOptions, 'method' | 'body'> = {}) =>
    request<T>(path, { ...options, method: 'GET' }),
  post: <T>(path: string, body?: unknown, options: Omit<RequestOptions, 'method' | 'body'> = {}) =>
    request<T>(path, { ...options, method: 'POST', body }),
  patch: <T>(path: string, body?: unknown, options: Omit<RequestOptions, 'method' | 'body'> = {}) =>
    request<T>(path, { ...options, method: 'PATCH', body }),
  put: <T>(path: string, body?: unknown, options: Omit<RequestOptions, 'method' | 'body'> = {}) =>
    request<T>(path, { ...options, method: 'PUT', body }),
  // C7：撤销注销需要带请求体传密码，因此 del 与 post/put 一样接受 body
  del: <T>(path: string, body?: unknown, options: Omit<RequestOptions, 'method' | 'body'> = {}) =>
    request<T>(path, { ...options, method: 'DELETE', body }),
};

/** 统一的用户可读错误文案 */
export function toFriendlyMessage(error: unknown): string {
  if (error instanceof OfflineError) return '网络连接失败，请检查网络后重试';
  if (error instanceof ApiError) {
    if (error.message) return error.message;
    return translateStatus(error.status);
  }
  return '操作失败，请稍后重试';
}

/** 金额格式化：分 → ¥0.00 */
export function formatPrice(cents: number | null | undefined): string {
  if (cents === null || cents === undefined) return '¥0.00';
  return `¥${(cents / 100).toFixed(2)}`;
}

/** 金额格式化（不带符号），用于「￥」单独渲染的场景 */
export function formatYuan(cents: number | null | undefined): string {
  if (cents === null || cents === undefined) return '0.00';
  return (cents / 100).toFixed(2);
}

export function formatDate(iso: string | null | undefined): string {
  if (!iso) return '—';
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return '—';
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(
    date.getHours(),
  )}:${pad(date.getMinutes())}`;
}

/**
 * 解析服务端时间戳（毫秒）。
 *
 * <h2>为什么不能直接用 new Date(raw)</h2>
 * 后端的秒杀活动时间字段是 Java `LocalDateTime`，序列化后形如 `2026-10-19T01:48:28`
 * —— **不带时区后缀**。而 ES 规范规定：这种形式按**运行环境本地时区**解析。
 * 服务端这个值其实是 **UTC 挂钟**（DB 一律存 UTC），于是浏览器在 +08:00 环境下
 * 会把它当成「北京时间 01:48」→ 比真实时间**早 8 小时**。
 *
 * 对倒计时来说这不是显示瑕疵，而是功能性错误（该结束的活动还在倒计时、已开始的显示未开始）。
 *
 * 因此：**带时区后缀（Z / ±HH:mm）的原样解析；不带的按 UTC 解析**。
 * 这条规则与后端「DB 存 UTC + LocalDateTime 无时区输出」是同一套口径。
 */
export function parseServerTime(raw: string | null | undefined): number {
  if (!raw) return NaN;
  const hasZone = /(?:Z|[+-]\d{2}:?\d{2})$/i.test(raw.trim());
  return new Date(hasZone ? raw : `${raw}Z`).getTime();
}

/** 按本地时区格式化服务端时间（口径见 {@link parseServerTime}） */
export function formatServerTime(raw: string | null | undefined): string {
  if (!raw) return '—';
  const ms = parseServerTime(raw);
  if (Number.isNaN(ms)) return '—';
  const date = new Date(ms);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(
    date.getHours(),
  )}:${pad(date.getMinutes())}`;
}

/** 生成幂等键：优先用 crypto.randomUUID，降级到时间戳+随机串 */
export function newIdempotentKey(): string {
  const c = globalThis.crypto;
  if (c && typeof c.randomUUID === 'function') return c.randomUUID();
  return `${Date.now()}-${Math.random().toString(36).slice(2, 12)}`;
}
