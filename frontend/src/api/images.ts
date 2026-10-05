/**
 * 图片资源解析（Web 端 · 与小程序 `miniprogram/utils/images.js` **同一套规则**）。
 *
 * <h2>为什么需要这一层</h2>
 * 后端 `t_spu.main_image` / `t_sku.image` 存的是 `/img/products/1001.svg` 这类**相对路径的 SVG**：
 * <ol>
 *   <li>这些文件在仓库里**从来不存在**（git 只跟踪过 `favicon.svg`），网关与商品服务也都不托管
 *       `/img` —— 直接塞进 `<img src>` 必然 404，这就是「Web 端商品图全部裂图」的根因
 *       （2026-10-05 用户报障）；</li>
 *   <li>小程序端早就为此做了解决方案（`utils/images.js`）：把这类路径映射到**包内本地位图**，
 *       注释里明确记着同样的报错教训。**Web 端一直缺这一层**。</li>
 * </ol>
 *
 * <h2>与小程序「同一套图片资源」怎么保证</h2>
 * <ul>
 *   <li><b>同一份物理文件</b>：`miniprogram/images/products/p01..p12.jpg` 原样拷贝到
 *       `frontend/public/img/products/`，两端渲染的是同一张图；</li>
 *   <li><b>同一套映射规则</b>：本文件是小程序解析器的移植（种子算法、取模、分支全部一致），
 *       所以**同一个 id 在两端永远得到同一张图**；</li>
 *   <li><b>同一数据源</b>：映射的「键」仍是后端下发的 `main_image` / `images`；
 *       将来接 CDN 时后端改下发绝对 https URL，本层自动走「原样使用」分支，两端零改动。</li>
 * </ul>
 *
 * <h2>★ 只重映射「已知不存在的命名空间」</h2>
 * 这条边界是本文件的命门：
 * <ul>
 *   <li>`/img/…` → 后端语义路径，**文件不存在**，重映射到本地图池；</li>
 *   <li>`/uploads/…` → **真实上传的文件**（fulfilment 服务托管、网关转发），必须原样放行 ——
 *       否则会把用户上传的闲置图误换成占位图；</li>
 *   <li>`http(s)://…` → 将来接 CDN，原样放行。</li>
 * </ul>
 * 写的时候若把「非绝对 URL 一律重映射」，上面的第 2 条就坏了 —— 这正是首版差点犯的错。
 *
 * <p>映射是<b>稳定</b>的：同一 id 永远得到同一张图，列表滚动 / 重进页面不会跳图。
 */

/** 图池大小：与小程序 `PRODUCT_COUNT` 一致（12 张） */
const PRODUCT_COUNT = 12;
/** 详情页轮播图集：主图 + 4 张邻近变体（与小程序 COUNT_PER_PRODUCT 一致） */
const COUNT_PER_PRODUCT = 5;

/** Web 端的本地图路径（文件与小程序包内逐字节同一份） */
function productPath(i: number): string {
  const n = ((i - 1) % PRODUCT_COUNT) + 1;
  return `/img/products/p${n < 10 ? '0' + n : n}.jpg`;
}

/** 从任意 id / 路径里取一个稳定的正整数种子（字符串 id 也不怕；算法与小程序一致） */
function seedOf(value: string | number | null | undefined): number {
  if (value === null || value === undefined || value === '') return 1;
  const s = String(value);
  let sum = 0;
  for (let i = 0; i < s.length; i++) sum = (sum * 31 + s.charCodeAt(i)) % 100000;
  // 纯数字 id 直接用末尾数字，保证「相邻商品取到相邻图」看起来更像真商品
  const tail = s.match(/(\d+)$/);
  if (tail) return Number(tail[1]) || sum;
  return sum || 1;
}

/**
 * 这条路径**真的存在**吗？
 *
 * `true`  = 原样放行（绝对 URL / 真实上传的 /uploads/）；
 * `false` = 后端的语义占位路径（/img/…），文件不存在，需要重映射。
 */
function isRealAsset(path: string | null | undefined): boolean {
  if (typeof path !== 'string' || path === '') return false;
  if (/^https?:\/\//i.test(path)) return true;
  // /uploads/** 由 fulfilment 服务托管、网关转发，是用户真实上传的文件
  if (path.startsWith('/uploads/')) return true;
  // 开发/生产若把前端静态资源挂到别的源，也可用相对路径直出 —— 这里只拦截已知的坏命名空间
  return !path.startsWith('/img/');
}

/** 商品主图：id 优先用 spuId（退化 skuId），backendPath 是后端下发的 main_image */
export function productImage(
  id: string | number | null | undefined,
  backendPath: string | null | undefined,
): string {
  if (isRealAsset(backendPath)) return backendPath as string;
  return productPath(seedOf(id));
}

/** SKU 缩略图：与所属商品保持一致观感 */
export function skuImage(
  skuId: string | number | null | undefined,
  spuId: string | number | null | undefined,
  backendPath: string | null | undefined,
): string {
  if (isRealAsset(backendPath)) return backendPath as string;
  return productPath(seedOf(spuId || skuId));
}

/** 详情页图集：主图 + 4 张邻近图（轮播，避免详情页只有一张图） */
export function gallery(
  id: string | number | null | undefined,
  backendPath: string | null | undefined,
): string[] {
  if (isRealAsset(backendPath)) return [backendPath as string];
  const base = seedOf(id);
  const list: string[] = [];
  for (let k = 0; k < COUNT_PER_PRODUCT; k++) list.push(productPath(base + k));
  return list;
}

/** 图文详情的配图：与轮播错开取图，稳定映射 */
export function detailImages(id: string | number | null | undefined): string[] {
  const base = seedOf(id) + 2;
  return [0, 1, 2].map((k) => productPath(base + k * 2));
}

/** 闲置（C2C）图：与商品共用同一池本地图（后端存的是 /img/listings/xxx.svg，同样不存在） */
export function listingImage(
  id: string | number | null | undefined,
  backendPath: string | null | undefined,
): string {
  if (isRealAsset(backendPath)) return backendPath as string;
  return productPath(seedOf(id));
}

/** 闲置详情图集：与商品图集同一逻辑；images 里真实存在的（如 /uploads/）原样保留 */
export function listingGallery(
  id: string | number | null | undefined,
  images: string[] | null | undefined,
  fallbackPath: string | null | undefined,
): string[] {
  const real = (images ?? []).filter((u) => isRealAsset(u));
  if (real.length > 0) return real;
  return gallery(id, fallbackPath);
}
