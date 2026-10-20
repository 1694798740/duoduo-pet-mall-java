/**
 * 定位能力封装（小程序端）。
 *
 * <h2>为什么需要单独一层</h2>
 * `wx.getLocation` / `wx.chooseLocation` 的失败原因**完全不是一回事**，
 * 但微信只给一句 `errMsg`，直接把 `errMsg` 抛给用户就会出现
 * 「明明是没声明接口，却提示你去开手机定位」这种误导。这里把三件事分开：
 * <ol>
 *   <li><b>能力/声明问题</b>（`NOT_DECLARED`）：app.json 的 `requiredPrivateInfos`
 *       没声明、或微信公众平台没申请「地理位置」接口权限 —— <b>用户无论怎么操作都没用</b>，
 *       提示必须指向开发者/管理员；</li>
 *   <li><b>授权问题</b>（`DENIED` / `SYSTEM`）：用户可以自己解决 →
 *       给「去设置」入口（`wx.openSetting`）；</li>
 *   <li><b>环境问题</b>（`SERVICE_OFF` / `TIMEOUT` / `UNKNOWN`）：提示重试或换手动选择。</li>
 * </ol>
 *
 * <h2>兜底原则</h2>
 * 无论哪一种失败，**省 / 市 / 区 下拉本身一直可用**，不依赖任何定位能力。
 * 所以所有失败文案都必须明确写「可直接用下方省市区选择」，并在页面上把用户
 * 引导到那个控件 —— 而不是让用户卡在一个只有"知道了"的弹窗里。
 */

const LOCATION_SCOPE = 'scope.userLocation';

/**
 * 把 `errMsg` 归类（**纯函数**，便于测试）。
 *
 * 真实 errMsg 样例（微信基础库）：
 *   · `getLocation:fail the api need to be declared in the requiredPrivateInfos field in app.json`
 *   · `chooseLocation:fail the api need to be declared in the requiredPrivateInfos field in app.json`
 *   · `getLocation:fail:system permission denied`
 *   · `getLocation:fail auth deny`
 *   · `chooseLocation:fail auth deny`
 *   · `chooseLocation:fail cancel`
 *   · `getLocation:fail timeout`
 *   · `getLocation:fail:ERROR_NOCELL&WIFI_LOCATIONSWITCHOFF`
 */
function classifyLocationError(errMsg) {
  const msg = String(errMsg || '').toLowerCase();
  if (!msg) return 'UNKNOWN';
  // 声明/能力问题：优先判断，否则会被下面的 auth 关键字误吞
  if (/requiredprivateinfos|need to be declared|not declared|privacy/.test(msg)) {
    return 'NOT_DECLARED';
  }
  if (/cancel/.test(msg)) return 'CANCELLED';
  if (/system permission denied|system error/.test(msg)) return 'SYSTEM';
  if (/auth deny|auth denied|authorize:fail|permission denied|no permission/.test(msg)) {
    return 'DENIED';
  }
  if (/timeout|time out/.test(msg)) return 'TIMEOUT';
  if (/location ?switch|locationswitchoff|gps|location service/.test(msg)) return 'SERVICE_OFF';
  return 'UNKNOWN';
}

/** 哪些原因值得弹「去设置」（只有用户自己能解决的才弹，避免无效打扰） */
function canOpenSetting(code) {
  return code === 'DENIED' || code === 'SYSTEM';
}

/**
 * 失败原因 → 面向用户的文案。
 *
 * @param code classifyLocationError 的结果
 * @param action 动作名（「定位」/「地图选点」），用于把句子说通
 */
function describeLocationError(code, action) {
  const act = action || '定位';
  const tail = '。可直接用下方「省 / 市 / 区」选择，不影响下单。';
  switch (code) {
    case 'NOT_DECLARED':
      // 用户无解：这是小程序声明/平台权限问题，不能让他去翻手机设置
      return `当前版本尚未开通${act}能力（接口未声明或未在微信平台申请权限），请联系管理员` + tail;
    case 'DENIED':
      return `${act}权限被拒绝，可在右上角「…」→ 设置里重新允许` + tail;
    case 'SYSTEM':
      return `系统未授予微信${act}权限，可在手机「设置 → 微信 → 位置」中开启` + tail;
    case 'SERVICE_OFF':
      return `手机定位服务未开启（或未连接网络定位），开启后重试` + tail;
    case 'TIMEOUT':
      return `${act}超时（信号弱或室内），可重试` + tail;
    default:
      return `${act}失败，可重试` + tail;
  }
}

/**
 * 调用定位前的授权检查。
 *
 * 三态处理：
 *   · `authSetting[scope] === true`  → 已授权，直接继续；
 *   · `=== false`                    → 曾拒绝，不再弹系统框（弹了也不会出现），直接返回 DENIED；
 *   · `undefined`                    → 从未询问，调 `wx.authorize` 触发系统授权框。
 */
function ensureLocationAuth() {
  return new Promise((resolve, reject) => {
    wx.getSetting({
      success: (res) => {
        const state = res && res.authSetting ? res.authSetting[LOCATION_SCOPE] : undefined;
        if (state === true) {
          resolve();
          return;
        }
        if (state === false) {
          reject({ code: 'DENIED' });
          return;
        }
        wx.authorize({
          scope: LOCATION_SCOPE,
          success: () => resolve(),
          fail: (err) => reject({ code: classifyLocationError(err && err.errMsg) }),
        });
      },
      fail: (err) => reject({ code: classifyLocationError(err && err.errMsg) }),
    });
  });
}

/** 打开设置页（仅在 canOpenSetting 为真时调用）；resolve(true) 表示用户回来时已授权 */
function openLocationSetting() {
  return new Promise((resolve) => {
    wx.openSetting({
      success: (res) => {
        const granted = !!(res && res.authSetting && res.authSetting[LOCATION_SCOPE]);
        resolve(granted);
      },
      fail: () => resolve(false),
    });
  });
}

module.exports = {
  LOCATION_SCOPE,
  classifyLocationError,
  describeLocationError,
  canOpenSetting,
  ensureLocationAuth,
  openLocationSetting,
};
