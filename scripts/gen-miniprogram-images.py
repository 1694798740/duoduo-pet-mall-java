#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
小程序图片资源生成器（A14 修复轮 · 图片补齐）

为什么要有这个脚本：
  1. 后端 t_spu.main_image 存的是 `/img/products/1001.svg` —— 既不是绝对 URL，
     网关(8080)/商品服务(8082) 也**不托管 /img**（实测 404/500）；且小程序 <image>
     不支持 SVG。所以「商品图不显示」不是没资源，而是**资源形态与端不匹配**。
  2. 本脚本用统一色板 + 统一尺寸生成一套本地位图（商品图 / 分类图标 / tabBar 图标），
     风格一致、体积可控（进主包），并保证每张图都能被小程序 <image> 正常渲染。

产物（miniprogram/images/）：
  products/p01..p12.jpg   600x600  商品占位图（同一模板、不同品类图形与配色）
  category/*.png          144x144  分类图标（透明底，语义名与 t_category.icon 对齐）
  tabbar/*.png            81x81    5 个 tab 的普通/选中图标（透明底）
  share/cover.jpg         500x400  分享封面

用法：python scripts/gen-miniprogram-images.py
"""
import os
import math
from PIL import Image, ImageDraw, ImageFont, ImageFilter

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'miniprogram', 'images')
FONT_DIR = 'C:/Windows/Fonts'

# ── 统一色板（与 app.wxss 的 CSS 变量一一对应）────────────────────────────────
BRAND = (79, 110, 247)      # --brand
ACCENT = (255, 91, 44)      # --accent
SUCCESS = (26, 173, 25)
INK = (35, 38, 47)          # --text
GREY = (156, 163, 175)      # --text-3

# 商品图配色：柔和双色渐变（浅→更浅），保证白/深色图形都清晰
PALETTES = [
    ((238, 241, 254), (214, 222, 252)),   # 蓝
    ((255, 241, 236), (255, 224, 212)),   # 橙
    ((232, 247, 238), (209, 240, 221)),   # 绿
    ((243, 238, 255), (226, 217, 255)),   # 紫
    ((230, 247, 250), (208, 238, 244)),   # 青
    ((255, 247, 230), (255, 235, 199)),   # 黄
]

# 商品图形（emoji 渲染，需要彩色 emoji 字体）
PRODUCT_GLYPHS = ['🐱', '🐶', '🐾', '🦴', '🥣', '🧴', '🪀', '🧺', '🛏️', '💊', '📡', '❤️']
PRODUCT_LABELS = ['猫主粮', '犬主粮', '宠物零食', '磨牙零食', '食具水具', '宠物洗护',
                  '逗猫玩具', '猫砂猫厕', '宠物窝垫', '健康医疗', '智能设备', '领养关注']

# 分类图标（键与后端 t_category.icon 语义名一致；值为 emoji）
CATEGORY_GLYPHS = {
    'bowl': '🥣', 'bone': '🦴', 'box': '📦',
    'medical': '💊', 'chip': '📡', 'heart': '❤️',
    'default': '🐾',
}
CATEGORY_BG = {
    'bowl': PALETTES[0], 'bone': PALETTES[1], 'box': PALETTES[3],
    'medical': PALETTES[2], 'chip': PALETTES[4], 'heart': PALETTES[1],
    'default': PALETTES[0],
}


def emoji_font(size):
    for name in ('seguiemj.ttf', 'SegoeUIEmoji.ttf'):
        p = os.path.join(FONT_DIR, name)
        if os.path.exists(p):
            return ImageFont.truetype(p, size)
    return ImageFont.load_default()


def cjk_font(size):
    for name in ('msyh.ttc', 'msyhbd.ttc', 'simhei.ttf', 'simsun.ttc'):
        p = os.path.join(FONT_DIR, name)
        if os.path.exists(p):
            try:
                return ImageFont.truetype(p, size)
            except Exception:
                continue
    return ImageFont.load_default()


def draw_glyph(img, glyph, size, center, font=None):
    """把 emoji 居中画到图上（embedded_color 让 emoji 保持彩色）"""
    if font is None:
        font = emoji_font(size)
    d = ImageDraw.Draw(img)
    box = d.textbbox((0, 0), glyph, font=font, embedded_color=True)
    w, h = box[2] - box[0], box[3] - box[1]
    d.text((center[0] - w / 2 - box[0], center[1] - h / 2 - box[1]), glyph,
           font=font, embedded_color=True)


def linear_bg(size, c1, c2, radius=48):
    """对角线性渐变底 + 圆角（透明角），带一点中心高光"""
    w, h = size
    grad = Image.new('RGB', (w, h))
    px = grad.load()
    for y in range(h):
        for x in range(w):
            t = (x / w * 0.45 + y / h * 0.55)
            px[x, y] = (
                int(c1[0] + (c2[0] - c1[0]) * t),
                int(c1[1] + (c2[1] - c1[1]) * t),
                int(c1[2] + (c2[2] - c1[2]) * t),
            )
    # 左上柔光，避免画面发闷
    glow = Image.new('L', (w, h), 0)
    gd = ImageDraw.Draw(glow)
    gd.ellipse((-w * 0.25, -h * 0.35, w * 0.75, h * 0.55), fill=70)
    glow = glow.filter(ImageFilter.GaussianBlur(w * 0.12))
    white = Image.new('RGB', (w, h), (255, 255, 255))
    grad = Image.composite(white, grad, glow.point(lambda v: int(v * 0.85)))

    mask = Image.new('L', (w, h), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, w - 1, h - 1), radius=radius, fill=255)
    out = Image.new('RGBA', (w, h), (0, 0, 0, 0))
    out.paste(grad, (0, 0), mask)
    return out


def make_product(path, glyph, _label, palette):
    """商品占位图（600×600，1:1）。

    刻意**不画任何文字**：早期版本在图上画了品类名（如「猫主粮」），
    但图片是按商品 id 稳定映射的，与真实商品并非一一对应 ——
    搜索结果里就出现过「猫罐头」配「领养关注」标签这种图文不符，
    反而让人误以为「搜索结果与关键词不匹配」。只保留图形与配色，交给页面渲染真实商品名。
    """
    S = 600
    img = linear_bg((S, S), palette[0], palette[1], radius=64)
    d = ImageDraw.Draw(img)
    # 中心柔光白圆 + 大图形 + 底部两道装饰弧（构图饱满但不抢戏）
    d.ellipse((S * 0.10, S * 0.08, S * 0.90, S * 0.88), fill=(255, 255, 255, 52))
    draw_glyph(img, glyph, int(S * 0.46), (S / 2, S * 0.48))
    d.arc((S * 0.22, S * 0.70, S * 0.78, S * 1.04), start=200, end=340,
          fill=(255, 255, 255, 120), width=6)
    d.arc((S * 0.32, S * 0.78, S * 0.68, S * 1.02), start=205, end=335,
          fill=(255, 255, 255, 90), width=5)
    img.convert('RGB').save(path, 'JPEG', quality=88, optimize=True)
    return os.path.getsize(path)


def make_category(path, glyph, palette):
    S = 144
    img = linear_bg((S, S), palette[0], palette[1], radius=44)
    draw_glyph(img, glyph, int(S * 0.62), (S / 2, S / 2 + 2))
    img.save(path, 'PNG', optimize=True)
    return os.path.getsize(path)


def icon_canvas():
    S = 81
    return Image.new('RGBA', (S, S), (0, 0, 0, 0))


def stroke(d, color, width):
    return dict(fill=color, outline=color, width=width)


def tab_home(color, S=81):
    img = icon_canvas()
    d = ImageDraw.Draw(img)
    w = 5
    d.line([(12, 40), (40.5, 15), (69, 40)], fill=color, width=w, joint='curve')
    d.rounded_rectangle((19, 38, 62, 68), radius=6, outline=color, width=w)
    d.rounded_rectangle((35, 50, 46, 68), radius=3, fill=color)
    return img


def tab_category(color, S=81):
    img = icon_canvas()
    d = ImageDraw.Draw(img)
    w = 5
    for cx, cy in ((24, 24), (57, 24), (24, 57), (57, 57)):
        d.rounded_rectangle((cx - 13, cy - 13, cx + 13, cy + 13), radius=6,
                            outline=color, width=w)
    return img


def tab_cart(color, S=81):
    img = icon_canvas()
    d = ImageDraw.Draw(img)
    w = 5
    d.line([(13, 18), (24, 18)], fill=color, width=w)
    d.line([(24, 18), (31, 50)], fill=color, width=w)
    d.line([(31, 50), (63, 50)], fill=color, width=w)
    d.line([(27, 28), (68, 28)], fill=color, width=w)
    d.line([(68, 28), (63, 50)], fill=color, width=w)
    d.ellipse((28, 57, 40, 69), outline=color, width=w)
    d.ellipse((54, 57, 66, 69), outline=color, width=w)
    return img


def tab_order(color, S=81):
    img = icon_canvas()
    d = ImageDraw.Draw(img)
    w = 5
    d.rounded_rectangle((20, 13, 61, 68), radius=7, outline=color, width=w)
    for y in (30, 42, 54):
        d.line([(30, y), (51, y)], fill=color, width=4)
    return img


def tab_profile(color, S=81):
    img = icon_canvas()
    d = ImageDraw.Draw(img)
    w = 5
    d.ellipse((29, 13, 52, 36), outline=color, width=w)
    d.arc((15, 40, 66, 88), start=200, end=340, fill=color, width=w)
    return img


def make_tabbar(outdir):
    tabs = {'home': tab_home, 'category': tab_category, 'cart': tab_cart,
            'order': tab_order, 'profile': tab_profile}
    total = 0
    for name, fn in tabs.items():
        for suffix, color in (('', GREY), ('-on', BRAND)):
            p = os.path.join(outdir, name + suffix + '.png')
            fn(color).save(p, 'PNG', optimize=True)
            total += os.path.getsize(p)
    return total


def make_banner(path, glyph, title, subtitle, palette):
    """首页轮播图：750:320（≈2.34:1）与首页轮播区高度一致，双色渐变 + 大图形 + 中英文案"""
    W, H = 1000, 427
    img = linear_bg((W, H), palette[0], palette[1], radius=32)
    d = ImageDraw.Draw(img)
    # 右侧大图形（留出左侧文案区）
    draw_glyph(img, glyph, 210, (W * 0.76, H * 0.50))
    f1 = cjk_font(52)
    f2 = cjk_font(28)
    d.text((56, H * 0.30), title, font=f1, fill=INK)
    d.text((56, H * 0.30 + 72), subtitle, font=f2, fill=(108, 114, 128))
    # 左下角胶囊（行动指引），与页面 tag 风格一致
    tag = '立即前往'
    tw = d.textlength(tag, font=f2)
    d.rounded_rectangle((56, H * 0.30 + 132, 56 + tw + 44, H * 0.30 + 132 + 52),
                        radius=26, fill=BRAND)
    d.text((56 + 22, H * 0.30 + 132 + 13), tag, font=f2, fill=(255, 255, 255))
    img.convert('RGB').save(path, 'JPEG', quality=88, optimize=True)
    return os.path.getsize(path)


def make_share(path):
    W, H = 500, 400
    img = linear_bg((W, H), (238, 241, 254), (214, 222, 252), radius=0)
    draw_glyph(img, '🐾', 150, (W * 0.5, H * 0.42))
    f = cjk_font(38)
    d = ImageDraw.Draw(img)
    text = '多多宠物商店'
    tw = d.textlength(text, font=f)
    d.text((W / 2 - tw / 2, H * 0.68), text, font=f, fill=BRAND)
    img.convert('RGB').save(path, 'JPEG', quality=88, optimize=True)
    return os.path.getsize(path)


def main():
    prod_dir = os.path.join(ROOT, 'products')
    cat_dir = os.path.join(ROOT, 'category')
    tab_dir = os.path.join(ROOT, 'tabbar')
    share_dir = os.path.join(ROOT, 'share')
    entry_dir = os.path.join(ROOT, 'entry')
    banner_dir = os.path.join(ROOT, 'banners')
    for d in (prod_dir, cat_dir, tab_dir, share_dir, entry_dir, banner_dir):
        os.makedirs(d, exist_ok=True)

    total = 0
    for i, (glyph, label) in enumerate(zip(PRODUCT_GLYPHS, PRODUCT_LABELS), start=1):
        p = os.path.join(prod_dir, 'p%02d.jpg' % i)
        total += make_product(p, glyph, label, PALETTES[(i - 1) % len(PALETTES)])
    print('products: 12 张')

    for key, glyph in CATEGORY_GLYPHS.items():
        total += make_category(os.path.join(cat_dir, key + '.png'), glyph, CATEGORY_BG[key])
    print('category: %d 张' % len(CATEGORY_GLYPHS))

    total += make_tabbar(tab_dir)
    print('tabbar: 10 张')

    # 首页快捷入口（宫格）图标：彩色圆角底 + 白色/彩色图形，与 tabBar 区分开
    entries = [
        ('seckill', '⚡', (255, 91, 44)),
        ('coupon', '🎫', (79, 110, 247)),
        ('ai', '🤖', (26, 173, 25)),
        ('category', '🧺', (146, 84, 255)),
    ]
    for name, glyph, _tint in entries:
        S = 108
        img = Image.new('RGBA', (S, S), (0, 0, 0, 0))
        d0 = ImageDraw.Draw(img)
        d0.rounded_rectangle((0, 0, S - 1, S - 1), radius=30,
                             fill=(255, 255, 255, 0))
        # 极浅底 + 主体 emoji，保持与商品图同一套柔和观感
        d0.rounded_rectangle((0, 0, S - 1, S - 1), radius=30,
                             fill=(246, 247, 253, 255))
        draw_glyph(img, glyph, int(S * 0.60), (S / 2, S / 2 + 1))
        img.save(os.path.join(entry_dir, name + '.png'), 'PNG', optimize=True)
    print('entry: %d 张' % len(entries))

    # 首页轮播图（Banner）：统一模板，方便后续替换/扩充（见 miniprogram/config/banners.js）
    banners = [
        ('b01', '🐱', '新客首单立减', '宠物主粮全场 8 折起', PALETTES[0]),
        ('b02', '⚡', '限时秒杀开抢', '整点场次 · 手快有手慢无', PALETTES[1]),
        ('b03', '🎫', '领券中心', '满 99 减 20，先领券再下单', PALETTES[2]),
        ('b04', '🤖', 'AI 导购「多多」', '不知道买什么？问一句就知道', PALETTES[4]),
    ]
    for name, glyph, title, subtitle, palette in banners:
        total += make_banner(os.path.join(banner_dir, name + '.jpg'), glyph, title, subtitle, palette)
    print('banners: %d 张' % len(banners))

    total += make_share(os.path.join(share_dir, 'cover.jpg'))
    print('share: 1 张')

    print('合计体积: %.1f KB（主包上限 2MB，占比 %.1f%%）'
          % (total / 1024, total / (2 * 1024 * 1024) * 100))


if __name__ == '__main__':
    main()
