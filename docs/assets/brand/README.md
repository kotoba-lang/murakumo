# murakumo press kit

murakumo (叢雲, "gathering clouds") is the Kotoba inference engine, a
[kotoba-lang](https://github.com/kotoba-lang) project.

The mark is four lanes stacked into a cloud. Each lane is a stream of tokens,
and the longest one, in murakumo blue, is the one being decoded. A network of
nodes gathers the same way: many lanes, one cloud.

## Files

| File | Use |
| --- | --- |
| `murakumo-mark-light.svg` | The mark alone, transparent, for light backgrounds |
| `murakumo-mark-dark.svg` | The mark alone, transparent, for dark backgrounds |
| `murakumo-logo-light.svg` | Mark and wordmark, transparent, for light backgrounds |
| `murakumo-logo-dark.svg` | Mark and wordmark, transparent, for dark backgrounds |
| `murakumo-icon.svg` | Square icon on ink, for avatars and app icons |
| `../header-light.svg`, `../header-dark.svg` | The README poster, drawn by `bench/poster.py` from a benchmark record |

The SVGs are the masters. The wordmark is live text (`ui-monospace`, then
SF Mono or Menlo; 叢雲 in Hiragino Sans or Noto Sans JP), not outlines.

## Colors

| Color | Hex | Use |
| --- | --- | --- |
| murakumo blue | `#4C5BD4` | The decoding lane on light backgrounds; the other lanes on dark ones |
| Bright | `#8C98FF` | The decoding lane on dark backgrounds |
| Soft | `#9AA3E8` | The other lanes on light backgrounds |
| Ink | `#0E1020` | Dark backgrounds, and the wordmark on light ones |
| Paper | `#ECEEF7` | The wordmark on dark backgrounds |

Secondary text is `#8A8FA8` on dark backgrounds and `#5A5F78` on light ones.

## Wording

The claim gate in Linear AWAI-18 governs speed wording. Until murakumo's own
engine has the fastest same-host decode (gate G1), say what it aims for and
print the measured numbers beside it:

- Yes: "murakumo, the Kotoba inference engine, aims for the fastest decode on
  the machine it runs on. On an M1 Max today: …" with the record's numbers,
  units, build and date.
- No: "the fastest", "faster than llama.cpp", or a murakumo tok/s figure
  without its record.
