#!/usr/bin/env python3
"""
Generates the app's icons from Lucide (https://lucide.dev, ISC license):
  - Android vector drawables: app/src/main/res/drawable/ic_<name>.xml
  - Web <symbol>s for web/index.html, printed to stdout (paste between the ICONS markers)

Usage:
  mkdir -p /tmp/lucide && cd /tmp/lucide
  for n in $(python3 tools/icons.py --list); do curl -sSfO https://cdn.jsdelivr.net/npm/lucide-static@0.468.0/icons/$n.svg; done
  python3 tools/icons.py /tmp/lucide

Lucide draws 24x24 outlines with a 2px round stroke; the drawables keep that and are tinted by the views.
"""
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

# Android drawable name -> Lucide icon
ANDROID = {
    "ic_back": "arrow-left", "ic_settings": "settings", "ic_camera": "camera", "ic_eye": "eye", "ic_eye_off": "eye-off",
    "ic_chevron": "chevron-right", "ic_play": "play", "ic_stop": "square", "ic_qr": "qr-code", "ic_share": "share-2",
    "ic_key": "key-round", "ic_dice": "dices", "ic_hash": "hash", "ic_mic": "mic", "ic_mic_off": "mic-off",
    "ic_screen_off": "moon", "ic_hd": "sparkles", "ic_frame": "ratio", "ic_speed": "gauge", "ic_lens": "aperture",
    "ic_chip": "cpu", "ic_globe": "globe", "ic_user": "user", "ic_check": "circle-check", "ic_external": "external-link",
    "ic_battery": "battery", "ic_info": "info", "ic_doc": "file-text", "ic_language": "languages", "ic_palette": "palette",
    "ic_server": "server", "ic_warn": "triangle-alert", "ic_heart": "heart", "ic_coffee": "coffee",
    "ic_thermo": "thermometer", "ic_smartphone": "smartphone", "ic_shield": "shield-check",
    "ic_sliders": "sliders-horizontal",
}
# Web symbol id -> Lucide icon
WEB = {
    "i-camera": "camera", "i-x": "x", "i-back": "arrow-left", "i-down": "chevron-down", "i-sliders": "sliders-horizontal",
    "i-rotate": "rotate-cw", "i-info": "info", "i-full": "maximize", "i-unfull": "minimize", "i-vol": "volume-2",
    "i-mute": "volume-x", "i-torch": "flashlight", "i-moon": "moon", "i-chev": "chevron-right", "i-alert": "triangle-alert",
    "i-camoff": "camera-off", "i-eye": "eye", "i-eyeoff": "eye-off", "i-qr": "qr-code", "i-gear": "settings",
    "i-image": "image", "i-more": "ellipsis-vertical", "i-trash": "trash-2", "i-key": "key-round", "i-shot": "aperture",
    "i-flip": "switch-camera", "i-heat": "thermometer", "i-battery": "battery-low", "i-plus": "plus", "i-scan": "scan-line",
    "i-heart": "heart", "i-coffee": "coffee",
}


def num(v):
    return float(v) if v not in (None, "") else 0.0


def fmt(x):
    s = f"{x:.3f}".rstrip("0").rstrip(".")
    return s if s not in ("-0", "") else "0"


def to_path(el):
    """SVG shape element -> path data (Android vectors only understand <path>)."""
    tag = el.tag.split("}")[-1]
    a = el.attrib
    if tag == "path":
        return a["d"]
    if tag == "circle":
        cx, cy, r = num(a.get("cx")), num(a.get("cy")), num(a.get("r"))
        return f"M{fmt(cx - r)},{fmt(cy)}a{fmt(r)},{fmt(r)} 0 1,0 {fmt(2 * r)},0a{fmt(r)},{fmt(r)} 0 1,0 {fmt(-2 * r)},0"
    if tag == "ellipse":
        cx, cy, rx, ry = num(a.get("cx")), num(a.get("cy")), num(a.get("rx")), num(a.get("ry"))
        return f"M{fmt(cx - rx)},{fmt(cy)}a{fmt(rx)},{fmt(ry)} 0 1,0 {fmt(2 * rx)},0a{fmt(rx)},{fmt(ry)} 0 1,0 {fmt(-2 * rx)},0"
    if tag == "line":
        return f"M{fmt(num(a['x1']))},{fmt(num(a['y1']))}L{fmt(num(a['x2']))},{fmt(num(a['y2']))}"
    if tag in ("polyline", "polygon"):
        pts = [float(p) for p in re.split(r"[\s,]+", a["points"].strip())]
        d = "M" + " L".join(f"{fmt(pts[i])},{fmt(pts[i + 1])}" for i in range(0, len(pts), 2))
        return d + ("z" if tag == "polygon" else "")
    if tag == "rect":
        x, y, w, h = num(a.get("x")), num(a.get("y")), num(a.get("width")), num(a.get("height"))
        rx = num(a.get("rx", a.get("ry"))); ry = num(a.get("ry", a.get("rx")))
        rx, ry = min(rx, w / 2), min(ry, h / 2)
        if not rx:
            return f"M{fmt(x)},{fmt(y)}h{fmt(w)}v{fmt(h)}h{fmt(-w)}z"
        return (f"M{fmt(x + rx)},{fmt(y)}h{fmt(w - 2 * rx)}a{fmt(rx)},{fmt(ry)} 0 0,1 {fmt(rx)},{fmt(ry)}"
                f"v{fmt(h - 2 * ry)}a{fmt(rx)},{fmt(ry)} 0 0,1 {fmt(-rx)},{fmt(ry)}h{fmt(-(w - 2 * rx))}"
                f"a{fmt(rx)},{fmt(ry)} 0 0,1 {fmt(-rx)},{fmt(-ry)}v{fmt(-(h - 2 * ry))}a{fmt(rx)},{fmt(ry)} 0 0,1 {fmt(rx)},{fmt(-ry)}z")
    raise ValueError(f"unsupported element {tag}")


def shapes(svg_file):
    root = ET.parse(svg_file).getroot()
    return [el for el in root.iter() if el.tag.split("}")[-1] in
            ("path", "circle", "ellipse", "line", "polyline", "polygon", "rect")]


def android_vector(svg_file):
    paths = "\n".join(
        f'    <path android:pathData="{to_path(el)}"\n'
        f'        android:strokeColor="#FFFFFFFF" android:strokeWidth="2" android:strokeLineCap="round" android:strokeLineJoin="round" />'
        for el in shapes(svg_file))
    return ('<?xml version="1.0" encoding="utf-8"?>\n'
            f'<!-- Lucide "{Path(svg_file).stem}" (ISC license), generated by tools/icons.py -->\n'
            '<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp"\n'
            '    android:viewportWidth="24" android:viewportHeight="24" android:tint="?android:attr/textColorPrimary">\n'
            f'{paths}\n</vector>\n')


def web_symbol(sid, svg_file):
    inner = "".join(f'<path d="{to_path(el)}"/>' for el in shapes(svg_file))
    return f'    <symbol id="{sid}" viewBox="0 0 24 24">{inner}</symbol>'


def main():
    if sys.argv[1:] == ["--list"]:
        print(" ".join(sorted(set(ANDROID.values()) | set(WEB.values()))))
        return
    src = Path(sys.argv[1])
    out = Path(__file__).resolve().parent.parent / "app/src/main/res/drawable"
    for name, icon in ANDROID.items():
        (out / f"{name}.xml").write_text(android_vector(src / f"{icon}.svg"))
    print("\n".join(web_symbol(sid, src / f"{icon}.svg") for sid, icon in WEB.items()))


if __name__ == "__main__":
    main()
