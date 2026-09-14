#!/usr/bin/env python3
"""给提取器画图标：深蓝圆角底 + 白色下箭头 + 下方一个小方块（代表 apk）。
输出到 res/mipmap-*/ic_launcher.png 各密度。"""
import os
from PIL import Image, ImageDraw

# 各密度边长
DENS = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}

def make(size):
    S = size
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    # 圆角底：深蓝渐变用两层叠出来（PIL 不直接给渐变，手动画横条）
    r = int(S * 0.22)
    for y in range(S):
        t = y / max(1, S - 1)
        c = (int(24 + 30 * t), int(60 + 50 * t), int(140 + 60 * t), 255)
        d.line([(0, y), (S, y)], fill=c)
    # 抠圆角
    mask = Image.new("L", (S, S), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, S - 1, S - 1], radius=r, fill=255)
    img.putalpha(mask)

    d = ImageDraw.Draw(img)

    # 白色向下箭头：竖杆 + 三角头
    w = S * 0.14
    cx = S / 2
    shaft_top = S * 0.18
    shaft_bot = S * 0.50
    d.rectangle([cx - w / 2, shaft_top, cx + w / 2, shaft_bot], fill=(255, 255, 255, 255))
    head_w = S * 0.30
    head_top = shaft_bot
    head_bot = S * 0.66
    d.polygon([
        (cx - head_w / 2, head_top),
        (cx + head_w / 2, head_top),
        (cx, head_bot),
    ], fill=(255, 255, 255, 255))

    # 底部托板（代表"下载目录"）
    pad_w = S * 0.56
    pad_h = S * 0.09
    pad_y = S * 0.78
    d.rounded_rectangle([
        cx - pad_w / 2, pad_y,
        cx + pad_w / 2, pad_y + pad_h
    ], radius=pad_h / 2, fill=(255, 255, 255, 235))

    # 右上角小方块（apk 包）
    b = S * 0.16
    bx = S * 0.70
    by = S * 0.14
    d.rounded_rectangle([bx, by, bx + b, by + b], radius=S * 0.035,
                        fill=(120, 220, 160, 255))
    return img

base = os.path.join(os.path.dirname(os.path.abspath(__file__)), "res")
for dens, size in DENS.items():
    outdir = os.path.join(base, "mipmap-" + dens)
    os.makedirs(outdir, exist_ok=True)
    make(size).save(os.path.join(outdir, "ic_launcher.png"))
    print("wrote", outdir + "/ic_launcher.png", size)
print("done")
