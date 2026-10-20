/**
 * 高德「微信小程序」Key 的本地配置文件（**示例模板**）。
 *
 * 真正的 `config/amap.js` 由 `scripts/miniprogram-amap-key.mjs` 从仓库根 `.env`
 * 的 `AMAP_MINI_KEY` 生成，且**不进 Git**（.gitignore 已排除）。
 * 这样做的原因：小程序包内的字符串天然可被解包看到，至少不要让它出现在仓库历史里。
 *
 * 生成命令：node scripts/miniprogram-amap-key.mjs
 * 校验命令：node scripts/miniprogram-amap-key.mjs --check
 *
 * ★ 这里放的必须是「微信小程序」类型的 Key。
 *   把「Web 服务」类型的 Key 放进来，调用会返回 USERKEY_PLAT_NOMATCH(10009)；
 *   反过来把小程序 Key 配到服务端，同样会被平台校验拒绝。
 */
module.exports = {
  key: '',
};
