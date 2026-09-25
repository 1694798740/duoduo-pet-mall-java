// A13/A14 · 小程序全局配置
//
// BASE_URL 指向网关（唯一对外入口）。三档环境在这里切换 CURRENT_ENV 即可：
//  * dev  → 开发者工具本地调试（需在「详情 → 本地设置」勾选「不校验合法域名」）
//  * lan  → 真机预览，换成开发机局域网 IP（手机与电脑须同一网段）
//  * prod → 上线，必须是 **HTTPS 域名**，且已加入小程序后台「request 合法域名」
//          （真机/体验版/正式版都强制校验合法域名，http 或未备案域名一律请求失败）
//
// 任何情况下都不要把 AppSecret 之类的密钥写进前端代码 —— 它们只存在于服务端 .env。
const ENV = {
  dev: 'http://localhost:8080',
  lan: 'http://192.168.1.100:8080', // 真机调试时改成自己的局域网 IP
  prod: 'https://api.your-domain.com', // 上线前替换为已备案的 HTTPS 域名
};

// 当前使用的环境：本机调试用 dev，真机预览用 lan，发布用 prod
const CURRENT_ENV = 'dev';

module.exports = {
  ENV: CURRENT_ENV,
  BASE_URL: ENV[CURRENT_ENV],

  // 微信登录（code2Session）开关：
  //  - true  → wx.login 的 code 换令牌（/api/auth/wechat/token）；
  //            未绑定微信的账号会返回 422，此时登录页会引导走手机号登录/注册；
  //  - false → 只用手机号 + 密码登录（测试号或后端未配置 AppID 时）。
  WECHAT_LOGIN_READY: true,
};
