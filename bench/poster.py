#!/usr/bin/env python3
"""Draw the README poster (docs/assets/header-{light,dark}.svg) from a run.

Reads bench/results/<run>/summary.json, so the poster is redrawn from the
record instead of retyped. murakumo's GPU-kernel bar is the 2026-10-19
record named in RECORDED (a Kotoba guest driving GLSL-built kernels
through a C loader that no longer builds).

  python3 -I bench/poster.py bench/results/2026-11-03-m1max
"""
import json, sys
from pathlib import Path
from xml.sax.saxutils import escape

ROOT = Path(__file__).resolve().parent.parent
RECORDED = {
    "label": "murakumo · Kotoba decode guest",
    "note": "2026-10-19 · GLSL kernels, C loader since removed",
    "value": 71.2,
}
THEMES = {
    "light": {"bg": "#F6F7FB", "ink": "#0E1020", "sub": "#5A5F78", "bar": "#C9CDE3", "brand": "#4C5BD4",
              "brand2": "#9AA3E8", "rule": "#E1E4F0"},
    "dark": {"bg": "#0E1020", "ink": "#ECEEF7", "sub": "#8A8FA8", "bar": "#2B3166", "brand": "#8C98FF",
             "brand2": "#4C5BD4", "rule": "#1C2040"},
}


def rows(summary):
    e = summary["engines"]
    k = summary["kernel_q8_0_matvec"]["amu-macos"]
    out = [
        ("MLX, 8-bit", "GPU", e["MLX (mlx_lm.benchmark)"]["decode_tok_s"]["median"], "other"),
        ("llama.cpp, Metal", "GPU · Q8_0", e["llama.cpp Metal"]["decode_tok_s"]["median"], "other"),
        ("llama.cpp, CPU", "1 thread · Q8_0", e["llama.cpp CPU, 1 thread"]["decode_tok_s"]["median"], "other"),
        (RECORDED["label"], RECORDED["note"], RECORDED["value"], "brand2"),
        ("llama.cpp, CPU", "8 threads · loaded host", e["llama.cpp CPU, 8 threads"]["decode_tok_s"]["median"], "other"),
        ("murakumo · Amu selfhost on this Mac", "one kernel's bound, not tokens", k["kernel_only_decode_bound_tok_s"], "brand"),
    ]
    return out


def svg(theme, data, run):
    t = THEMES[theme]
    w, h = 1200, 560
    left, right, top, step = 470, 1040, 170, 56
    vmax = max(v for _, _, v, _ in data)
    parts = [
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {w} {h}" width="{w}" height="{h}" role="img" '
        f'aria-label="{escape(alt(data))}">',
        f'<rect width="{w}" height="{h}" rx="18" fill="{t["bg"]}"/>',
        mark(56, 44, 1.0, t),
        f'<text x="128" y="78" font-family="ui-monospace, SFMono-Regular, Menlo, monospace" font-size="38" '
        f'font-weight="700" fill="{t["ink"]}">murakumo</text>',
        f'<text x="336" y="78" font-family="Hiragino Sans, Noto Sans JP, sans-serif" font-size="30" '
        f'fill="{t["sub"]}">叢雲</text>',
        f'<text x="128" y="110" font-family="ui-sans-serif, -apple-system, Helvetica, Arial, sans-serif" '
        f'font-size="19" fill="{t["sub"]}">The Kotoba inference engine. Goal: the fastest decode on the machine it runs on. '
        f'Not there yet.</text>',
        f'<line x1="56" y1="136" x2="{w - 56}" y2="136" stroke="{t["rule"]}" stroke-width="2"/>',
        f'<text x="{w - 56}" y="162" text-anchor="end" font-family="ui-sans-serif, -apple-system, Helvetica, Arial, sans-serif" '
        f'font-size="15" fill="{t["sub"]}">decode, tokens/s · Qwen2.5-0.5B-Instruct · MacBook Pro M1 Max 32 GB · {run[:10]}</text>',
    ]
    for i, (name, note, v, kind) in enumerate(data):
        y = top + 24 + i * step
        fill = t["brand"] if kind == "brand" else t["brand2"] if kind == "brand2" else t["bar"]
        weight = "700" if kind != "other" else "500"
        bw = max(4, (right - left) * v / vmax)
        parts += [
            f'<text x="56" y="{y + 4}" font-family="ui-sans-serif, -apple-system, Helvetica, Arial, sans-serif" '
            f'font-size="19" font-weight="{weight}" fill="{t["ink"]}">{escape(name)}</text>',
            f'<text x="56" y="{y + 25}" font-family="ui-sans-serif, -apple-system, Helvetica, Arial, sans-serif" '
            f'font-size="14" fill="{t["sub"]}">{escape(note)}</text>',
            f'<rect x="{left}" y="{y - 14}" width="{bw:.1f}" height="28" rx="6" fill="{fill}"/>',
            f'<text x="{left + bw + 12:.1f}" y="{y + 7}" font-family="ui-monospace, SFMono-Regular, Menlo, monospace" '
            f'font-size="19" font-weight="{weight}" fill="{t["ink"]}">{v:.1f}</text>',
        ]
    parts.append(
        f'<text x="56" y="{h - 26}" font-family="ui-sans-serif, -apple-system, Helvetica, Arial, sans-serif" '
        f'font-size="14" fill="{t["sub"]}">Median of three rounds on a shared, loaded Mac. Record: '
        f'bench/results/{run}. murakumo bars are not yet a served engine.</text>')
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
    return "murakumo, the Kotoba inference engine. Decode tokens per second on a MacBook Pro M1 Max: " + "; ".join(
        f"{n} ({note}) {v:.1f}" for n, note, v, _ in data) + "."


def main(run_dir):
    run_dir = Path(run_dir)
    data = rows(json.loads((run_dir / "summary.json").read_text()))
    run = run_dir.name
    for theme in THEMES:
        (ROOT / "docs/assets" / f"header-{theme}.svg").write_text(svg(theme, data, run))
    print(alt(data))


if __name__ == "__main__":
    main(sys.argv[1])
