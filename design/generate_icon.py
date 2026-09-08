# -*- coding: utf-8 -*-
"""
记账日报 · 复古报纸风桌面图标生成器
- 从系统楷体提取「账」字轮廓（fontTools）
- 生成 legacy 512 SVG（双线框报纸版面 + 印章）、round 512 SVG、adaptive foreground 108 SVG
- 生成 VectorDrawable (ic_launcher_foreground.xml)
- 调 Edge headless 栅格化 legacy PNG（各密度）
用法: py design/generate_icon.py
"""
import os
import subprocess
import sys

from fontTools.ttLib import TTFont
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.pens.boundsPen import BoundsPen
from fontTools.misc.transform import Transform

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DESIGN = os.path.join(ROOT, "design")
RES = os.path.join(ROOT, "app", "src", "main", "res")
# Edge 路径自动探测（Windows 常见安装位置）
EDGE_CANDIDATES = [
    r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
    r"C:\Program Files\Microsoft\Edge\Application\msedge.exe",
]
EDGE = next((c for c in EDGE_CANDIDATES if os.path.exists(c)), EDGE_CANDIDATES[0])

# 与 App 内部一致的配色
PAPER = "#F5EFE2"
CARD = "#FBF6EA"
INK = "#221D15"
INK_SOFT = "#5C5346"
RULE = "#C9BFA8"
RED = "#A63A2B"

CHAR = "账"
FONT_CANDIDATES = [os.path.join(os.environ.get("WINDIR", "C:\Windows"), "Fonts", f)
                   for f in ("simkai.ttf", "simsun.ttc")]


def glyph_path(char, size, cx, cy):
    """提取字形轮廓，缩放到 size 见方、中心对齐 (cx, cy)，返回 SVG path d（Y 已向下）。"""
    font = None
    for path in FONT_CANDIDATES:
        if os.path.exists(path):
            font = TTFont(path, fontNumber=0)
            break
    if font is None:
        sys.exit("no Chinese font found")
    cmap = font.getBestCmap()
    glyph_name = cmap[ord(char)]
    glyph_set = font.getGlyphSet()
    glyph = glyph_set[glyph_name]

    bp = BoundsPen(glyph_set)
    glyph.draw(bp)
    xmin, ymin, xmax, ymax = bp.bounds
    s = size / max(xmax - xmin, ymax - ymin)
    tcx, tcy = (xmin + xmax) / 2, (ymin + ymax) / 2
    # 字体坐标 Y 向上 → 屏幕 Y 向下：scale(s, -s)，平移使字形中心落在 (cx, cy)
    t = Transform(s, 0, 0, -s, cx - s * tcx, cy + s * tcy)

    pen = SVGPathPen(glyph_set)
    glyph.draw(TransformPen(pen, t))
    return pen.getCommands(), round(s, 4)


def rrect_path(x, y, w, h):
    """直角矩形 path（VectorDrawable 用）。"""
    return f"M{x},{y} h{w} v{h} h-{w} Z"


def fmt(v):
    s = f"{v:.2f}".rstrip("0").rstrip(".")
    return s if s else "0"


# ---------------------------------------------------------------- legacy 512
def build_legacy_svg(d_char):
    W = 512
    parts = []
    parts.append(
        f'<rect x="0" y="0" width="{W}" height="{W}" rx="100" fill="{PAPER}"/>')
    # 双线框（同 App 内 bg_dialog_paper）
    parts.append(f'<rect x="26" y="26" width="460" height="460" fill="none" stroke="{INK}" stroke-width="6"/>')
    parts.append(f'<rect x="42" y="42" width="428" height="428" fill="none" stroke="{INK}" stroke-width="2"/>')

    # 报纸主体
    parts.append(f'<rect x="96" y="112" width="320" height="288" fill="{CARD}" stroke="{INK}" stroke-width="6"/>')

    # 报头：- ■ -
    parts.append(f'<rect x="120" y="146" width="36" height="14" fill="{INK}"/>')
    parts.append(f'<rect x="172" y="140" width="168" height="26" fill="{INK}"/>')
    parts.append(f'<rect x="356" y="146" width="36" height="14" fill="{INK}"/>')
    # 题下粗规则线
    parts.append(f'<rect x="120" y="188" width="272" height="6" fill="{INK}"/>')

    # 左栏：迷你柱状图（红柱 + 两根墨框斜纹柱），底边 y=336
    parts.append(f'<rect x="132" y="264" width="26" height="72" fill="{RED}"/>')
    hatch_bars = [(174, 288, 48), (216, 278, 58)]
    for bx, by, bh in hatch_bars:
        parts.append(f'<rect x="{bx}" y="{by}" width="26" height="{bh}" fill="none" stroke="{INK}" stroke-width="4"/>')
    parts.append('<clipPath id="hb1"><rect x="178" y="292" width="18" height="40"/></clipPath>')
    parts.append('<g clip-path="url(#hb1)" stroke="%s" stroke-width="3">' % INK)
    for i in range(6):
        x0 = 168 + i * 12
        parts.append(f'<line x1="{x0}" y1="336" x2="{x0 + 40}" y2="292"/>')
    parts.append('</g>')
    parts.append('<clipPath id="hb2"><rect x="220" y="282" width="18" height="50"/></clipPath>')
    parts.append('<g clip-path="url(#hb2)" stroke="%s" stroke-width="3">' % INK)
    for i in range(6):
        x0 = 210 + i * 12
        parts.append(f'<line x1="{x0}" y1="336" x2="{x0 + 40}" y2="282"/>')
    parts.append('</g>')

    # 右栏：文字行
    widths = [124, 104, 124, 112, 124, 88]
    y = 214
    for wline in widths:
        parts.append(f'<rect x="268" y="{y}" width="{wline}" height="10" fill="{INK_SOFT}"/>')
        y += 21

    # 底部虚线引导线
    parts.append(f'<line x1="120" y1="362" x2="392" y2="362" stroke="{RULE}" stroke-width="3" stroke-dasharray="7,6"/>')

    # 印章：右下角，旋转 -10°
    d, s = d_char
    parts.append('<g transform="translate(388,372) rotate(-10)">')
    parts.append(f'<rect x="-64" y="-64" width="128" height="128" fill="{RED}"/>')
    parts.append(f'<rect x="-49" y="-49" width="98" height="98" fill="none" stroke="{PAPER}" stroke-width="5"/>')
    parts.append(f'<path d="{d}" fill="{PAPER}"/>')
    parts.append('</g>')

    body = "\n".join(parts)
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{W}" '
            f'viewBox="0 0 {W} {W}">\n{body}\n</svg>')


# ---------------------------------------------------------------- round 512
def build_round_svg(d_char):
    W = 512
    parts = []
    parts.append(f'<circle cx="256" cy="256" r="256" fill="{PAPER}"/>')
    parts.append(f'<circle cx="256" cy="256" r="238" fill="none" stroke="{INK}" stroke-width="4"/>')
    # 报纸主体（与 legacy 相同的内部版面）
    parts.append(f'<rect x="96" y="112" width="320" height="288" fill="{CARD}" stroke="{INK}" stroke-width="6"/>')
    parts.append(f'<rect x="120" y="146" width="36" height="14" fill="{INK}"/>')
    parts.append(f'<rect x="172" y="140" width="168" height="26" fill="{INK}"/>')
    parts.append(f'<rect x="356" y="146" width="36" height="14" fill="{INK}"/>')
    parts.append(f'<rect x="120" y="188" width="272" height="6" fill="{INK}"/>')
    parts.append(f'<rect x="132" y="264" width="26" height="72" fill="{RED}"/>')
    for bx, by, bh in [(174, 288, 48), (216, 278, 58)]:
        parts.append(f'<rect x="{bx}" y="{by}" width="26" height="{bh}" fill="none" stroke="{INK}" stroke-width="4"/>')
    parts.append('<clipPath id="rb1"><rect x="178" y="292" width="18" height="40"/></clipPath>')
    parts.append(f'<g clip-path="url(#rb1)" stroke="{INK}" stroke-width="3">')
    for i in range(6):
        x0 = 168 + i * 12
        parts.append(f'<line x1="{x0}" y1="336" x2="{x0 + 40}" y2="292"/>')
    parts.append('</g>')
    parts.append('<clipPath id="rb2"><rect x="220" y="282" width="18" height="50"/></clipPath>')
    parts.append(f'<g clip-path="url(#rb2)" stroke="{INK}" stroke-width="3">')
    for i in range(6):
        x0 = 210 + i * 12
        parts.append(f'<line x1="{x0}" y1="336" x2="{x0 + 40}" y2="282"/>')
    parts.append('</g>')
    widths = [124, 104, 124, 112, 124, 88]
    y = 214
    for wline in widths:
        parts.append(f'<rect x="268" y="{y}" width="{wline}" height="10" fill="{INK_SOFT}"/>')
        y += 21
    parts.append(f'<line x1="120" y1="362" x2="392" y2="362" stroke="{RULE}" stroke-width="3" stroke-dasharray="7,6"/>')
    d, s = d_char
    parts.append('<g transform="translate(388,372) rotate(-10)">')
    parts.append(f'<rect x="-64" y="-64" width="128" height="128" fill="{RED}"/>')
    parts.append(f'<rect x="-49" y="-49" width="98" height="98" fill="none" stroke="{PAPER}" stroke-width="5"/>')
    parts.append(f'<path d="{d}" fill="{PAPER}"/>')
    parts.append('</g>')
    body = "\n".join(parts)
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{W}" '
            f'viewBox="0 0 {W} {W}">\n{body}\n</svg>')


# ------------------------------------------------------- adaptive foreground
def build_fg_svg(d_char):
    """108 画布，内容收在中心 r=33 安全圆内。"""
    parts = []
    # 报纸
    parts.append(f'<rect x="32" y="34" width="44" height="40" fill="{CARD}" stroke="{INK}" stroke-width="2"/>')
    # 报头
    parts.append(f'<rect x="38" y="41" width="22" height="4" fill="{INK}"/>')
    parts.append(f'<rect x="36" y="48" width="26" height="1.6" fill="{INK}"/>')
    # 左栏迷你柱状图（细省：红柱 + 两根墨框柱）
    parts.append(f'<rect x="39" y="54" width="4" height="10" fill="{RED}"/>')
    parts.append(f'<rect x="46" y="57" width="4" height="7" fill="none" stroke="{INK}" stroke-width="1.2"/>')
    parts.append(f'<rect x="53" y="55.5" width="4" height="8.5" fill="none" stroke="{INK}" stroke-width="1.2"/>')
    # 右栏文字行
    for i, wline in enumerate([11, 9, 11, 7]):
        parts.append(f'<rect x="61" y="{54 + i * 3.4}" width="{wline}" height="1.7" fill="{INK_SOFT}"/>')
    # 虚线（用短段模拟，VectorDrawable 不支持 dash）
    for i in range(4):
        parts.append(f'<rect x="{36 + i * 7}" y="69" width="4" height="1.4" fill="{RULE}"/>')
    # 印章
    parts.append('<g transform="translate(66,64) rotate(-10)">')
    parts.append(f'<rect x="-11" y="-11" width="22" height="22" fill="{RED}"/>')
    parts.append(f'<rect x="-8.5" y="-8.5" width="17" height="17" fill="none" stroke="{PAPER}" stroke-width="1.4"/>')
    parts.append(f'<path d="{d_char[0]}" fill="{PAPER}"/>')
    parts.append('</g>')
    body = "\n".join(parts)
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="108" height="108" '
            f'viewBox="0 0 108 108">\n{body}\n</svg>')


def build_foreground_xml(d_char):
    """VectorDrawable 108dp。"""
    d, s = d_char
    seal = (
        '<group android:rotation="-10" android:pivotX="66" android:pivotY="64">\n'
        f'        <path android:pathData="M55,53 h22 v22 h-22 Z" android:fillColor="{RED}"/>\n'
        '        <path android:pathData="M57.5,55.5 h17 v17 h-17 Z" android:strokeColor="%s" android:strokeWidth="1.4"/>\n'
        f'        <path android:pathData="{d}" android:fillColor="{PAPER}"/>\n'
        '    </group>' % PAPER)
    return f'''<?xml version="1.0" encoding="utf-8"?>
<!-- 复古报纸风前景：报纸版面 + 右下「账」字印章。生成自 design/generate_icon.py -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path android:pathData="{rrect_path(32, 34, 44, 40)}" android:fillColor="{CARD}" android:strokeColor="{INK}" android:strokeWidth="2"/>
    <path android:pathData="{rrect_path(38, 41, 22, 4)}" android:fillColor="{INK}"/>
    <path android:pathData="{rrect_path(36, 48, 26, 1.6)}" android:fillColor="{INK}"/>
    <path android:pathData="{rrect_path(39, 54, 4, 10)}" android:fillColor="{RED}"/>
    <path android:pathData="{rrect_path(46, 57, 4, 7)}" android:strokeColor="{INK}" android:strokeWidth="1.2"/>
    <path android:pathData="{rrect_path(53, 55.5, 4, 8.5)}" android:strokeColor="{INK}" android:strokeWidth="1.2"/>
    <path android:pathData="{rrect_path(61, 54, 11, 1.7)}" android:fillColor="{INK_SOFT}"/>
    <path android:pathData="{rrect_path(61, 57.4, 9, 1.7)}" android:fillColor="{INK_SOFT}"/>
    <path android:pathData="{rrect_path(61, 60.8, 11, 1.7)}" android:fillColor="{INK_SOFT}"/>
    <path android:pathData="{rrect_path(61, 64.2, 7, 1.7)}" android:fillColor="{INK_SOFT}"/>
    <path android:pathData="M36,69 h4 v1.4 h-4 Z" android:fillColor="{RULE}"/>
    <path android:pathData="M43,69 h4 v1.4 h-4 Z" android:fillColor="{RULE}"/>
    <path android:pathData="M50,69 h4 v1.4 h-4 Z" android:fillColor="{RULE}"/>
    <path android:pathData="M57,69 h4 v1.4 h-4 Z" android:fillColor="{RULE}"/>
    {seal}
</vector>
'''


def rasterize(svg_path, png_path, size):
    # SVG 固有尺寸不会随窗口缩放，用 HTML 包装器强制铺满窗口
    wrapper = os.path.join(DESIGN, "_wrap.html")
    with open(wrapper, "w", encoding="utf-8") as f:
        f.write('<!DOCTYPE html><html><head><meta charset="utf-8"><style>'
                "*{margin:0;padding:0}img{display:block;width:100vw;height:100vh}"
                f'</style></head><body><img src="file:///{svg_path.replace(chr(92), "/")}"></body></html>')
    cmd = [
        EDGE, "--headless", "--disable-gpu", "--hide-scrollbars",
        "--force-device-scale-factor=1",
        "--default-background-color=00000000",
        f"--window-size={size},{size}",
        f"--screenshot={png_path}",
        "file:///" + wrapper.replace("\\", "/"),
    ]
    subprocess.run(cmd, check=True, capture_output=True, timeout=120)


def main():
    os.makedirs(DESIGN, exist_ok=True)
    # 印章字：legacy 512 里 76px；fg 108 里 13px
    d_legacy, _ = glyph_path(CHAR, 76, 0, 0)
    d_fg, _ = glyph_path(CHAR, 13, 0, 0)

    svgs = {
        "icon_legacy.svg": build_legacy_svg((d_legacy, 0)),
        "icon_round.svg": build_round_svg((d_legacy, 0)),
        "icon_foreground.svg": build_fg_svg((d_fg, 0)),
    }
    for name, svg in svgs.items():
        with open(os.path.join(DESIGN, name), "w", encoding="utf-8") as f:
            f.write(svg)

    # VectorDrawable 前景
    with open(os.path.join(RES, "drawable", "ic_launcher_foreground.xml"), "w",
              encoding="utf-8") as f:
        f.write(build_foreground_xml((d_fg, 0)))

    # adaptive icon 定义（背景 = 纸色，前景 = 报纸 + 印章，monochrome 同前景）
    anydpi = os.path.join(RES, "mipmap-anydpi-v26")
    os.makedirs(anydpi, exist_ok=True)
    for name, mono in [("ic_launcher.xml", "true"), ("ic_launcher_round.xml", "true")]:
        mono_line = ('\n        <monochrome android:drawable="@drawable/ic_launcher_foreground"/>'
                     if mono else "")
        with open(os.path.join(anydpi, name), "w", encoding="utf-8") as f:
            f.write(f'''<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/paper"/>
    <foreground android:drawable="@drawable/ic_launcher_foreground"/>{mono_line}
</adaptive-icon>
''')

    # legacy PNG：替换模板 webp
    densities = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
    legacy_svg = os.path.abspath(os.path.join(DESIGN, "icon_legacy.svg"))
    round_svg = os.path.abspath(os.path.join(DESIGN, "icon_round.svg"))
    for folder, size in densities.items():
        d = os.path.join(RES, f"mipmap-{folder}")
        os.makedirs(d, exist_ok=True)
        for old in ("ic_launcher.webp", "ic_launcher_round.webp"):
            p = os.path.join(d, old)
            if os.path.exists(p):
                os.remove(p)
        rasterize(legacy_svg, os.path.join(d, "ic_launcher.png"), size)
        rasterize(round_svg, os.path.join(d, "ic_launcher_round.png"), size)

    # 预览大图 + 最小尺寸可读性检查图
    rasterize(legacy_svg, os.path.join(DESIGN, "preview_512.png"), 512)
    rasterize(legacy_svg, os.path.join(DESIGN, "preview_48.png"), 48)
    rasterize(os.path.abspath(os.path.join(DESIGN, "icon_foreground.svg")),
              os.path.join(DESIGN, "preview_fg_432.png"), 432)
    print("done")


if __name__ == "__main__":
    main()
