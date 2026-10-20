/**
 * 省 / 市 / 区县三级行政区划（A1 地址改造）。
 *
 * 数据来历：从公开的 `pcas-code.json`（省/市/区/街道四级）裁成三级，
 * 并把直辖市的「市辖区」归一成省份同名（北京市 / 北京市 / 东城区）——
 * 这也是后端 `fullAddress` 去重逻辑所依赖的形态。
 *
 * 为什么内置而不是每次调接口：
 *  · 级联下拉必须在**断网/接口慢**时也能用（用户在填地址，不能卡住）；
 *  · 不依赖地图 Key（Key 只用于「定位回填」这一条路径）；
 *  · 两端共用同一份数据，网页与小程序选出来的名称完全一致。
 *
 * ★ 体积：约 110KB（gzip 后 ~30KB）。用**动态 import** 单独切块 ——
 *   只有打开地址页/地址弹窗时才会拉，不进商城首屏。
 *
 * ★ 数据只覆盖大陆 31 个省级单位（不含港澳台）。若历史地址里存在数据外的名称，
 *   调用方必须把「当前值」作为额外选项补进下拉，否则编辑时会被清空（见 Addresses.tsx）。
 */

export interface RegionDistrict {
  /** 名称 */
  n: string;
  /** 行政区划代码 */
  c: string;
}

export interface RegionCity {
  n: string;
  c: string;
  ch: RegionDistrict[];
}

export interface RegionProvince {
  n: string;
  c: string;
  ch: RegionCity[];
}

export interface RegionData {
  v: string;
  list: RegionProvince[];
}

let cache: RegionData | null = null;
let inflight: Promise<RegionData> | null = null;

/** 加载（并缓存）省市区数据；并发调用共享同一个请求 */
export function loadRegions(): Promise<RegionData> {
  if (cache) return Promise.resolve(cache);
  if (!inflight) {
    inflight = import('./regions.json').then((mod) => {
      cache = (mod.default ?? mod) as unknown as RegionData;
      inflight = null;
      return cache;
    });
  }
  return inflight;
}

/** 仅测试用：清掉缓存 */
export function __resetRegionsCache(): void {
  cache = null;
  inflight = null;
}
