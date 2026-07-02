# infer-prewarm — populate worker rpc caches straight from HuggingFace

The llama.cpp RPC engine caches every ≥10 MB tensor a worker receives under
`~/Library/Caches/llama.cpp/rpc/<fnv1a64-of-bytes>` (`rpc-server -c`), and the
head sends `SET_TENSOR_HASH` first — a cache hit means the tensor's bytes never
cross the wire. These tools exploit that: **compute each worker's tensor hashes
from the GGUF offline, and let every worker curl its own tensors as HTTP RANGE
requests against the HF CDN** — so a WAN-attached head (0.6–3 MB/s to the fleet)
can conduct a 139 GB model load in minutes, because the bulk bytes flow
HF→worker at line speed instead of head→worker over the WAN.

Flow (run on the machine that has the GGUF + llama.cpp checkout):

    cc -O3 -o fnv fnv.c
    uvx --from gguf python gen_manifest.py 8,6,6,6,6,6,6,6,6,6,6,10 emit
    python3 hash_manifests.py                       # one mmap pass per part
    # per node: scp manifests/<node>.txt fetch.sh <node>: && ssh <node> sh fetch.sh <node>.txt

Notes:
- `gen_manifest.py` REPLICATES llama.cpp's layer→device rule
  (`upper_bound(splits, il/act_gpu_layers)`, act = n_layer+1) — the tensor-split
  vector you pass here must be the one you pass to `llama-server`. A mismatch is
  safe but slow: misses fall back to full sends over the head link (and the
  worker caches them for next time).
- Dense leading layers (GLM-5.2: 3) weigh ~1/10 of a MoE layer — the first
  shard can take extra layers; verify with the printed per-node byte table.
- Tensors ≤10 MB (norms etc.) always stream directly — a few hundred KB total.
