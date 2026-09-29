// 顶部通知小卡片（第五十五轮）
//
// 形态：顶部滑入 → 图标 + 标题 + 摘要 + 时间 → 4 秒自动收起 → 点击进落地页。
//
// 设计取向：这是「提醒」，不是「页面」。用户此刻正在浏览商品，卡片不该打断他 ——
// 所以：① 同一时刻只显示一条（新的覆盖旧的，由页面控制）；② 到点自动消失；
// ③ 不占用导航栏与底部操作区，只压一小条；④ 有未读数字的地方（tabBar 角标）
//    仍然独立生效，卡片消失不等于提醒消失。
//
// ★ 组件是「哑」的：不拉数据、不判冷却、不做跳转。数据与冷却策略在
//   utils/notice-popup.js，跳转与标已读由页面处理（页面才知道要跳到哪、要不要标已读）。
//   一个组件只做一件事，才能被单独测试，也才能在别的页面复用。
const AUTO_HIDE_MS = 4000;

/** 收起动画时长，必须与 wxss 里 .toast-out 的 transition 时长一致 */
const LEAVE_MS = 260;

Component({
  properties: {
    /** 业务开关：true 时出场并开始 4 秒倒计时 */
    show: { type: Boolean, value: false },
    icon: { type: String, value: '🔔' },
    title: { type: String, value: '' },
    content: { type: String, value: '' },
    timeText: { type: String, value: '' },
  },

  data: {
    /** 正在播放收起动画（动画结束后才真正隐藏，避免卡片「啪」地消失） */
    leaving: false,
    /** 状态栏高度：卡片贴在状态栏下方，不压住时间与信号图标 */
    statusBarHeight: 20,
  },

  lifetimes: {
    attached() {
      let statusBarHeight = 20;
      try {
        const info = wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync();
        if (info && info.statusBarHeight) statusBarHeight = info.statusBarHeight;
      } catch (e) {}
      this.setData({ statusBarHeight });
    },
    detached() {
      // 组件销毁必须清定时器：否则页面切走后定时器仍会触发 setData（小程序会告警）
      this.clearTimer();
    },
  },

  observers: {
    show(value) {
      this.clearTimer();
      if (value) {
        this.setData({ leaving: false });
        this._timer = setTimeout(() => this.hide(), AUTO_HIDE_MS);
      }
    },
  },

  methods: {
    clearTimer() {
      if (this._timer) {
        clearTimeout(this._timer);
        this._timer = null;
      }
    },

    /** 收起：先播动画（260ms），再通知页面把 show 置 false */
    hide() {
      this.clearTimer();
      if (this.data.leaving) {
        return;
      }
      this.setData({ leaving: true });
      this._leaveTimer = setTimeout(() => {
        this._leaveTimer = null;
        this.setData({ leaving: false });
        this.triggerEvent('close');
      }, LEAVE_MS);
    },

    /** 点击：立刻收起（不等 4 秒），由页面决定标已读与跳转 */
    onTap() {
      this.hide();
      this.triggerEvent('tapnotice');
    },
  },
});
