"""Modal's GPU catalogue: rate, VRAM, memory bandwidth. One copy, not two.

`qwen38_27b_bench.py` imports RATE_USD_PER_SEC from here rather than carrying
its own dict, because a benchmark that prices tokens off a stale table reports
a wrong number in the same format as a right one.

Read 2026-08-21 from modal.com/pricing and modal.com/docs/guide/gpu. Bandwidth
is the card's published spec (Modal does not publish it), and is what decode
throughput tracks -- see MEASURED_EFFECTIVE_BANDWIDTH below for how far the
spec is from what we actually got.

Prices change. Re-read the pricing page before quoting these; the failure mode
is that a number with a date on it gets copied without the date.
"""

# gpu= string -> (VRAM GB nameplate, memory type, bandwidth GB/s, USD/second)
CATALOGUE = {
    "T4":           (16,  "GDDR6",   320, 0.000164),
    "L4":           (24,  "GDDR6",   300, 0.000222),
    "A10":          (24,  "GDDR6",   600, 0.000306),
    "L40S":         (48,  "GDDR6",   864, 0.000542),
    "A100-40GB":    (40,  "HBM2",   1555, 0.000583),
    "A100-80GB":    (80,  "HBM2e",  2039, 0.000694),
    "RTX-PRO-6000": (96,  "GDDR7",  1597, 0.000842),
    "H100":         (80,  "HBM3",   3350, 0.001097),
    "H200":         (141, "HBM3e",  4800, 0.001261),
    "B200":         (180, "HBM3e",  8000, 0.001736),
    "B300":         (288, "HBM3e",  8000, 0.001972),
}

RATE_USD_PER_SEC = {k: v[3] for k, v in CATALOGUE.items()}
BANDWIDTH_GB_S = {k: v[2] for k, v in CATALOGUE.items()}

# Modal bills CPU and memory separately from the GPU. A $/token figure that
# counts only the GPU line understates the cheap cards most: at 8 core/32 GiB
# the side charge is +32% on an L40S and +12% on an H200.
CPU_USD_PER_CORE_SEC = 0.0000131
MEM_USD_PER_GIB_SEC = 0.00000222

# Silent substitutions. Each of these can hand you a different card than the
# one you priced.
ALIAS_UPGRADES = {
    "H100": "may auto-upgrade to H200 at the H100 price; write 'H100!' to pin "
            "the hardware for benchmarking (Modal GPU guide, read 2026-08-21).",
    "A100": "bare 'A100' may auto-upgrade to the 80GB part. 'A100-40GB' "
            "requests the 40GB part, but one measured request still got 80GB; "
            "inspect the reported device and VRAM before comparing results.",
    "B200": "'B200+' may auto-upgrade to B300 and is billed as B200 -- "
            "the one upgrade that is free, so prefer 'B200+' over 'B200'.",
}

# What torch reported as total_memory inside a real container, versus the
# nameplate. Measured 2026-08-20..21.
OBSERVED_VRAM_GIB = {
    "L40S": [44.39, 47.37],   # two different values from the same gpu= string
    "H100": [79.18],
    "H200": [139.8],
    "B200": [178.35],
    "RTX-PRO-6000": [94.97],
}

# Qwen3.8-27B-FP8, MTP off, single stream, ~28 GB of weights read per token.
# gpu -> (measured tok/s, implied GB/s, fraction of spec).
# The point of this table: spec bandwidth over-predicts, and it over-predicts
# WORSE the faster the card. Do not convert catalogue GB/s to tok/s linearly.
MEASURED_EFFECTIVE_BANDWIDTH = {
    "RTX-PRO-6000": (45.5, 1274, 0.80),
    "H100":         (78.2, 2190, 0.65),
    "L40S":         (18.8,  526, 0.61),
    "H200":         (93.9, 2629, 0.55),
}


def usd_per_sec(gpu: str, cpu_cores: int = 8, mem_gib: int = 32) -> float:
    """All-in rate: GPU plus Modal's separate CPU and memory lines."""
    return (
        RATE_USD_PER_SEC[gpu]
        + cpu_cores * CPU_USD_PER_CORE_SEC
        + mem_gib * MEM_USD_PER_GIB_SEC
    )


if __name__ == "__main__":
    print(f"{'gpu=':<14}{'VRAM':>6}{'mem':>7}{'GB/s':>7}{'$/s':>10}"
          f"{'$/hr':>8}{'$/mo 730h':>11}{'GB/s per $/hr':>15}")
    for g, (v, m, bw, ps) in CATALOGUE.items():
        hr = ps * 3600
        print(f"{g:<14}{v:>5}G{m:>7}{bw:>7,}{ps:>10.6f}{hr:>8.2f}"
              f"{hr*730:>11,.0f}{bw/hr:>15,.0f}")
