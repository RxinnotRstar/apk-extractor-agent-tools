#!/usr/bin/env python3
"""给提取器画图标。

层次（从外到内）：
    1. 牛皮纸/瓦楞纸色外圈（最外层，可切 flat / corrugated / none）
    2. 深蓝渐变圆角底
    3. 白色下箭头 + 底部托板 + 右上角 apk 小方块

用法：
    python3 make_icon.py                 # 按 RING_VARIANT 生成 res/mipmap-*/*
    python3 make_icon.py flat            # 指定外圈样式
    python3 make_icon.py --preview       # 只输出 192px 对比预览到 preview/
"""
import os
import sys
from PIL import Image, ImageDraw, ImageChops

# 各密度边长
DENS = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}

# 牛皮纸 / 瓦楞纸配色
KRAFT = (199, 161, 107, 255)       # #C7A16B 主体
KRAFT_DARK = (166, 127, 77, 255)   # #A67F4D 瓦楞暗纹 / 内侧描边

RING_VARIANT = "flat"              # flat | corrugated | none


def base_plate(S):
    """深蓝渐变圆角底，返回 (图, 圆角半径)"""
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    for y in range(S):
        t = y / max(1, S - 1)
        d.line([(0, y), (S, y)],
               fill=(int(24 + 30 * t), int(60 + 50 * t), int(140 + 60 * t), 255))
    r = int(S * 0.22)
    mask = Image.new("L", (S, S), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, S - 1, S - 1], radius=r, fill=255)
    img.putalpha(mask)
    return img, r


def content(img, S):
    """白色下箭头 + 托板 + 右上角小方块"""
    d = ImageDraw.Draw(img)
    cx = S / 2.0
    # 竖杆
    w = S * 0.14
    d.rectangle([cx - w / 2, S * 0.18, cx + w / 2, S * 0.50], fill=(255, 255, 255, 255))
    # 三角头
    hw = S * 0.30
    d.polygon([(cx - hw / 2, S * 0.50), (cx + hw / 2, S * 0.50), (cx, S * 0.66)],
              fill=(255, 255, 255, 255))
    # 底部托板
    pw, ph, py = S * 0.56, S * 0.09, S * 0.78
    d.rounded_rectangle([cx - pw / 2, py, cx + pw / 2, py + ph],
                        radius=ph / 2, fill=(255, 255, 255, 235))
    # 右上角 apk 小方块
    b = S * 0.16
    d.rounded_rectangle([S * 0.70, S * 0.14, S * 0.70 + b, S * 0.14 + b],
                        radius=S * 0.035, fill=(120, 220, 160, 255))
    return img


def ring_layer(S, r, variant):
    """最外层的牛皮纸色圆环。variant: flat / corrugated / none"""
    if variant == "none":
        return None

    t = max(1, int(round(S * 0.065)))          # 环带厚度
    band = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    bd = ImageDraw.Draw(band)
    bd.rounded_rectangle([0, 0, S - 1, S - 1], radius=r, fill=KRAFT)

    if variant == "corrugated":
        # 竖向瓦楞纹：暗色细条，稍后会被裁在环带里
        flute = max(2, int(round(S * 0.024)))
        half = max(1, flute // 2)
        x = 0
        while x < S:
            bd.rectangle([x, 0, x + half, S - 1], fill=KRAFT_DARK)
            x += flute

    # 挖掉内圈 => 只剩环带
    hole = Image.new("L", (S, S), 255)
    ImageDraw.Draw(hole).rounded_rectangle(
        [t, t, S - 1 - t, S - 1 - t], radius=max(1, r - t), fill=0)
    band.putalpha(ImageChops.multiply(band.getchannel("A"), hole))

    # 环带内缘压一道暗线，出立体感
    if variant == "flat":
        bd.rounded_rectangle([t, t, S - 1 - t, S - 1 - t],
                             radius=max(1, r - t),
                             outline=KRAFT_DARK, width=max(1, int(S * 0.008)))
    return band


def render(S, variant):
    img, r = base_plate(S)
    content(img, S)
    band = ring_layer(S, r, variant)
    if band is not None:
        img = Image.alpha_composite(img, band)
    return img


def preview():
    panels = [render(192, "none"), render(192, "flat"), render(192, "corrugated")]
    gap = 18
    W = 192 * len(panels) + gap * (len(panels) + 1)
    H = 192 + gap * 2
    canvas = Image.new("RGBA", (W, H), (238, 238, 241, 255))
    x = gap
    for p in panels:
        canvas.alpha_composite(p, (x, gap))
        x += 192 + gap
    outdir = os.path.join(os.path.dirname(os.path.abspath(__file__)), "preview")
    os.makedirs(outdir, exist_ok=True)
    out = os.path.join(outdir, "icon_variants.png")
    canvas.convert("RGB").save(out)
    print("wrote", out)
    print("panel 1 = 现在的样子(none) / panel 2 = flat / panel 3 = corrugated")


if __name__ == "__main__":
    args = [a for a in sys.argv[1:]]
    if "--preview" in args:
        preview()
        sys.exit(0)

    variant = RING_VARIANT
    for a in args:
        if a in ("flat", "corrugated", "none"):
            variant = a

    base = os.path.join(os.path.dirname(os.path.abspath(__file__)), "res")
    for dens, size in DENS.items():
        outdir = os.path.join(base, "mipmap-" + dens)
        os.makedirs(outdir, exist_ok=True)
        render(size, variant).save(os.path.join(outdir, "ic_launcher.png"))
        print("wrote", outdir + "/ic_launcher.png", size, "variant=" + variant)
    print("done")
