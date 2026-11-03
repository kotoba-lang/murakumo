# Existing Bonsai model server observations

Around 06:08 UTC on 2026-11-03, a read-only SSH process-list sample was taken
on the ten Tailscale Mac minis using the existing identity and pinned host
keys. The test selected process executable basenames `llama-server`,
`mlx_lm.server` and `mtplx` only; it did not inspect or retain arbitrary
arguments, environment variables or credentials. All ten hosts responded to
the process query. This name-based sample is not an exhaustive inventory of
model processes or their descendants.

Joseph and Zebulun each had a `llama-server` process whose `-m` argument
ended in `Ternary-Bonsai-2-27B-PTQ1_0.gguf`. At the observed instant Joseph
reported about 8,468 MiB RSS and Zebulun about 9 MiB RSS; RSS is not total
unified-memory or GPU usage and these figures do not prove the models' actual
resident footprint. Both processes reported loopback port 18094, context
32768, parallel 1 and `-ngl 999`. Their loopback HTTP `/health` returned
`ok` and `/v1/models` listed the corresponding GGUF path. No inference request
was sent.

The inspected process arguments did not include the checked draft-model or
speculative-decoding flags. This does not prove the server lacks MTP or that
its defaults cannot use it. It is not evidence of the requested Ternary
Bonsai 2 27B MTP distributed inference, a usable fleet endpoint, controlled
access, governor admission, sustained capacity, or bot integration. Both
listeners were bound to loopback; no port or service configuration was
changed. Process lists and health responses are point-in-time observations.
Existing services and model files were left untouched.
