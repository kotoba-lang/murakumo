#!/usr/bin/env python3
"""Draw the README poster (docs/assets/header-{light,dark}.svg) from bench/poster.json.

poster.json holds the numbers, each taken from a dated record under bench/results/ (named in its footer), so the
poster is redrawn from the records instead of retyped. Each group is scaled to its own largest value.

  python3 -I bench/poster.py [bench/poster.json]
"""
import json, sys
from pathlib import Path
from xml.sax.saxutils import escape

ROOT = Path(__file__).resolve().parent.parent
SANS = "ui-sans-serif, -apple-system, Helvetica, Arial, sans-serif"
MONO = "ui-monospace, SFMono-Regular, Menlo, monospace"
THEMES = {
    "light": {"bg": "#F6F7FB", "ink": "#0E1020", "sub": "#5A5F78", "bar": "#C9CDE3", "brand": "#4C5BD4",
              "brand2": "#9AA3E8", "muted": "#E3E5F0", "rule": "#E1E4F0"},
    "dark": {"bg": "#0E1020", "ink": "#ECEEF7", "sub": "#8A8FA8", "bar": "#2B3166", "brand": "#8C98FF",
             "brand2": "#4C5BD4", "muted": "#1F2448", "rule": "#1C2040"},
}
W, LEFT, RIGHT, ROW, GROUP_GAP = 1200, 470, 1040, 50, 30


def fmt(v):
    return f"{v:,.0f}" if v >= 1000 else f"{v:.1f}"


def height(data):
    return 190 + sum(40 + ROW * len(g["rows"]) + GROUP_GAP for g in data["groups"]) + 30


def svg(theme, data):
    t = THEMES[theme]
    h = height(data)
    parts = [
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W} {h}" width="{W}" height="{h}" role="img" '
        f'aria-label="{escape(alt(data))}">',
        f'<rect width="{W}" height="{h}" rx="18" fill="{t["bg"]}"/>',
        mark(56, 44, 1.0, t),
        f'<text x="128" y="78" font-family="{MONO}" font-size="38" font-weight="700" fill="{t["ink"]}">murakumo</text>',
        f'<text x="336" y="78" font-family="Hiragino Sans, Noto Sans JP, sans-serif" font-size="30" '
        f'fill="{t["sub"]}">叢雲</text>',
        f'<text x="128" y="110" font-family="{SANS}" font-size="19" fill="{t["sub"]}">{escape(data["tagline"])}</text>',
        f'<line x1="56" y1="136" x2="{W - 56}" y2="136" stroke="{t["rule"]}" stroke-width="2"/>',
        f'<text x="{W - 56}" y="162" text-anchor="end" font-family="{SANS}" font-size="15" '
        f'fill="{t["sub"]}">{escape(data["host"])} · {escape(data["date"])}</text>',
    ]
    y = 190
    for g in data["groups"]:
        parts.append(f'<text x="56" y="{y + 10}" font-family="{SANS}" font-size="16" font-weight="700" '
                     f'letter-spacing="0.5" fill="{t["sub"]}">{escape(g["title"].upper())}</text>')
        y += 40
        vmax = max(r["value"] for r in g["rows"])
        for r in g["rows"]:
            kind = r["kind"]
            fill = t.get(kind, t["bar"]) if kind != "other" else t["bar"]
            weight = "700" if kind == "brand" else "500"
            ink = t["sub"] if kind == "muted" else t["ink"]
            bw = max(4, (RIGHT - LEFT) * r["value"] / vmax)
            parts += [
                f'<text x="56" y="{y + 2}" font-family="{SANS}" font-size="18" font-weight="{weight}" '
                f'fill="{ink}">{escape(r["name"])}</text>',
                f'<text x="56" y="{y + 21}" font-family="{SANS}" font-size="13" fill="{t["sub"]}">'
                f'{escape(r["note"])}</text>',
                f'<rect x="{LEFT}" y="{y - 12}" width="{bw:.1f}" height="26" rx="6" fill="{fill}"'
                + (f' stroke="{t["sub"]}" stroke-dasharray="4 3"' if kind == "muted" else "") + '/>',
                f'<text x="{LEFT + bw + 12:.1f}" y="{y + 7}" font-family="{MONO}" font-size="18" '
                f'font-weight="{weight}" fill="{ink}">{fmt(r["value"])}</text>',
            ]
            y += ROW
        y += GROUP_GAP
    parts.append(f'<text x="56" y="{h - 26}" font-family="{SANS}" font-size="13" fill="{t["sub"]}">'
                 f'{escape(data["footer"])}</text>')
    parts.append("</svg>")
    return "\n".join(parts) + "\n"


def mark(x, y, s, t):
    lanes = [(18, 0, 18), (8, 10, 36), (0, 20, 52), (10, 30, 34)]
    out = [f'<g transform="translate({x} {y}) scale({s})">']
    for i, (lx, ly, lw) in enumerate(lanes):
        fill = t["brand"] if i == 2 else t["brand2"]
        out.append(f'<rect x="{lx}" y="{ly}" width="{lw}" height="7" rx="3.5" fill="{fill}"/>')
    out.append("</g>")
    return "".join(out)


def alt(data):
    groups = "; ".join(
        g["title"] + ": " + ", ".join(f'{r["name"]} ({r["note"]}) {fmt(r["value"])}' for r in g["rows"])
        for g in data["groups"])
    return f'murakumo, the Kotoba inference engine. {data["host"]}, {data["date"]}. {groups}.'


def main(path):
    data = json.loads(Path(path).read_text())
    for theme in THEMES:
        (ROOT / "docs/assets" / f"header-{theme}.svg").write_text(svg(theme, data))
    print(alt(data))


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else ROOT / "bench/poster.json")
