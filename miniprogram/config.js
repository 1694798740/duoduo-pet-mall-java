// A13 · 小程序全局配置
// BASE_URL 指向网关（唯一对外入口）。开发者工具本地调试：勾选「不校验合法域名」。
// 真机预览时改为局域网 IP（如 http://192.168.x.x:8080）；上线前必须是 HTTPS 域名并进小程序后台白名单。
module.exports = {
  BASE_URL: 'http://localhost:8080',
  // 微信登录（code2Session）开关：
  //  - 后端 /api/auth/wechat 端点在 A13 下一轮落地（AppID/secret 走环境变量，绝不落盘）；
  //  - 端点未就绪或未配置 AppID 时，登录回退到「手机号 + 密码」（复用既有 /api/auth/login），
  //    保证测试号阶段整条主交易闭环可走通。
  WECHAT_LOGIN_READY: true,
};
