// A14 · 图片资源解析（小程序端）
//
// 背景（为什么需要这一层）：
//  后端 t_spu.main_image / t_sku.image 存的是 `/img/products/1001.svg` 这类**相对路径的 SVG**：
//   1. 相对路径在 <image> 里会被当成小程序包内路径 → 必然加载失败（控制台
//      「Failed to load ... /img/products/1008.svg」就是这么来的）；
//   2. 那些文件是 Web 前端的静态资源，网关(8080) 与商品服务(8082) 都**不托管 /img**（实测 404/500）；
//   3. 小程序 <image> 组件**不支持 SVG**，必须是 png/jpg/webp。
//  所以：绝对 URL（http/https，将来接 CDN）原样使用；其余一律映射到随包的本地位图，
//  保证「任何商品都有图、比例统一（1:1）、风格一致」，且不依赖外网。
//
// 约定：
//  * 映射是**稳定**的（同一 spuId/skuId 永远得到同一张图），列表滚动/重进页面不会跳图；
//  * 本地图总量 297KB（12 商品 + 7 分类 + 10 tabBar + 1 封面），主包 2MB 上限内；
//  * 想换成真实商品图：把位图放 CDN，让后端 main_image 下发**绝对 https URL** 即可，
//    本层会自动走「绝对 URL 原样使用」分支，前端零改动。

const PRODUCT_COUNT = 12;
const COUNT_PER_PRODUCT = 5; // 详情页轮播图集：主图 + 4 张邻近变体（第四十二轮 3→5）

function productPath(i) {
  const n = ((i - 1) % PRODUCT_COUNT) + 1;
  return '/images/products/p' + (n < 10 ? '0' + n : n) + '.jpg';
}

/** 从任意 id / 路径里取一个稳定的正整数种子（字符串 id 也不怕） */
function seedOf(value) {
  if (value === null || value === undefined || value === '') return 1;
  const s = String(value);
  let sum = 0;
  for (let i = 0; i < s.length; i++) sum = (sum * 31 + s.charCodeAt(i)) % 100000;
  // 纯数字 id 直接用末两位，保证「相邻商品取到相邻图」看起来更像真商品
  const tail = s.match(/(\d+)$/);
  if (tail) return Number(tail[1]) || sum;
  return sum || 1;
}

function isAbsolute(path) {
  return typeof path === 'string' && /^https?:\/\//i.test(path);
}

/**
 * 商品主图。
 * @param {string|number} id 业务 id（spuId 优先，退化用 skuId）
 * @param {string} backendPath 后端下发的 main_image（可能是绝对 URL，也可能是 /img/xxx.svg）
 */
function productImage(id, backendPath) {
  if (isAbsolute(backendPath)) return backendPath; // 将来接 CDN：原样使用
  return productPath(seedOf(id));
}

/** SKU 缩略图：与所属商品的图保持一致观感 */
function skuImage(skuId, spuId, backendPath) {
  if (isAbsolute(backendPath)) return backendPath;
  return productPath(seedOf(spuId || skuId));
}

/** 详情页图集：主图 + 4 张邻近图（swiper 轮播，避免详情页只有一张图） */
function gallery(id, backendPath) {
  if (isAbsolute(backendPath)) {
    return [backendPath];
  }
  const base = seedOf(id);
  const list = [];
  for (let k = 0; k < COUNT_PER_PRODUCT; k++) {
    list.push(productPath(base + k));
  }
  return list;
}

/** 图文详情的配图：与轮播错开取图，稳定映射（第四十二轮） */
function detailImages(id) {
  const base = seedOf(id) + 2;
  return [0, 1, 2].map((k) => productPath(base + k * 2));
}

// 分类图标：键与后端 t_category.icon 的语义名对齐（后端存的是 bowl / bone / box / …）
const CATEGORY_ICONS = {
  bowl: '/images/category/bowl.png',
  bone: '/images/category/bone.png',
  box: '/images/category/box.png',
  medical: '/images/category/medical.png',
  chip: '/images/category/chip.png',
  heart: '/images/category/heart.png',
  default: '/images/category/default.png',
};

function categoryIcon(icon) {
  if (isAbsolute(icon)) return icon;
  if (!icon) return CATEGORY_ICONS.default;
  // 后端可能给语义名（bone），也可能给整条路径（/img/categories/bone.svg），统一取末段
  const key = String(icon).replace(/^.*\//, '').replace(/\.[a-z0-9]+$/i, '');
  return CATEGORY_ICONS[key] || CATEGORY_ICONS.default;
}

/** 通用资源解析：绝对 URL / 包内路径原样，其余（/img/xxx）退化为默认占位图 */
function assetUrl(path, fallback) {
  if (isAbsolute(path)) return path;
  if (typeof path === 'string' && path.indexOf('/images/') === 0) return path;
  return fallback || CATEGORY_ICONS.default;
}

module.exports = {
  productImage: productImage,
  skuImage: skuImage,
  gallery: gallery,
  detailImages: detailImages,
  categoryIcon: categoryIcon,
  assetUrl: assetUrl,
  CATEGORY_ICONS: CATEGORY_ICONS,
};
