// 首页轮播图配置（Banner）
//
// 想替换/增减轮播图，**只改这个文件**，页面代码无需改动：
//   · image    包内路径（/images/banners/xxx.jpg）或 https 绝对地址（接 CDN 时直接换）
//   · url      点击后跳转的页面路径；留空表示不可点击
//   · overlay  是否在图上叠加标题/副标题文字（图内已带文案时保持 false，换成纯图片素材时置 true）
//
// 图片规格约定：750:320（≈2.34:1），与首页轮播区高度一致；
// 随包素材由 scripts/gen-miniprogram-images.py 统一生成，保证风格、比例一致。
module.exports = {
  // 自动轮播间隔（毫秒）
  interval: 4000,
  // 切换动画时长（毫秒）：数值越大过渡越平滑
  duration: 420,
  // 是否自动播放（需要手动控制时置 false）
  autoplay: true,

  list: [
    {
      id: 'b01',
      image: '/images/banners/b01.jpg',
      title: '新客首单立减',
      subtitle: '宠物主粮全场 8 折起',
      url: '/pages/category/category',
      overlay: false,
    },
    {
      id: 'b02',
      image: '/images/banners/b02.jpg',
      title: '限时秒杀开抢',
      subtitle: '整点场次 · 手快有手慢无',
      url: '/pages/seckill/seckill',
      overlay: false,
    },
    {
      id: 'b03',
      image: '/images/banners/b03.jpg',
      title: '领券中心',
      subtitle: '满 99 减 20，先领券再下单',
      url: '/pages/coupons/coupons',
      overlay: false,
    },
    {
      id: 'b04',
      image: '/images/banners/b04.jpg',
      title: 'AI 导购「多多」',
      subtitle: '不知道买什么？问一句就知道',
      url: '/pages/ai/ai',
      overlay: false,
    },
  ],
};
