# Bounded resident state and pin inputs

The resident now checks regular-file sizes and range-reads config, saved lease,
head, candidate head, environment, policy, private runtime and restored state
before parsing or use. Config, heads and restored state are capped at 16384
bytes; saved lease at 4096; environment, policy and runtime at 131072. Those
limits can refuse formerly accepted inputs. Runtime and restored-state limits
match the existing recovery path. This is not an audit of every dependency or
all resident IO; in particular, the token, model transport and checkpoint
closure still require their own controls.

A failed or oversized bounded read yields an empty vector. Comparing its CID
with an explicitly configured empty CID must not make the unreadable file look
valid, so `rt-pins-ok?` now requires nonempty environment and policy reads
before checking their CIDs. Empty environment or policy files previously
pinned by an operator now refuse rather than being accepted.

Selected source and historical dependencies passed the pinned Amu source
check. A signed local native fixture with diagnostic FS2/JSON/CID created
oversized environment and policy files, set their expected pins to the empty
CID, and observed rejection for each. A source mutant removing the nonempty
checks failed the fixture. The positive Flint public environment and archive
acknowledgement fixture also passed again. The negative fixture source SHA-256
was `16abf86928d00d8d633da6aeb89c7d2c514b20e9f545d83a6363b55de347a13b`;
signed image SHA-256 was
`14627db768b1b623d66369149d43dbf3d51280148be0ea1328aee3759bee6087`.
The updated positive fixture source SHA-256 was
`32125d13b8246b0a265d025ef7bd5a7458aa0334dc5f6bd930a8866c8af8db0f`.

The host must own paths and exclude aliases and same-size replacements, and
the provider must enforce the range bounds. This test did not separately
exercise every new cap, inject FS2 failures, run live quorum or GitHub, execute
a model, or qualify selfhost, a production runtime or the resource governor.
