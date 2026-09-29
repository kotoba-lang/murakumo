# ADR-260929: Distributed web plane — search / extract / crawl

**Status**: Proposed (design only; nothing below is implemented)
**Date**: 2026-09-29
**Scope**: murakumo (placement), kototama (receipts), yataverse (bytes)

## Context

A survey on 2026-09-29 of the local repos found no decentralized equivalent of
Exa / Parallel (search), Tavily (search for agents) or Firecrawl
(extract + crawl):

- Search is a single self-hosted SearXNG Deployment (gftdcojp ADR-2605220300).
  Tavily was rejected there for cost/dependency/privacy; the result is
  self-hosted, not decentralized.
- Crawling is per-purpose bots (hyakka family: evidence script → proposal gate →
  PR) driven by Hermes profiles and cron. There is no general crawl/extract API.
- murakumo's `/v1` is inference-only; its nodes already carry `:labels {:tier
  :zone}`, DIDs, heartbeat and job placement.
- yataverse-distribution plans CID replica placement, but its murakumo-overlay
  transport is unlanded (ADR-2607023100).

The FAL.ai analogue (media generation) is out of scope here; it is a separate,
already partly built plane (`murakumo-generation`).

## Decision

Model the three capabilities as **job kinds on the existing murakumo job plane**,
with **results stored as CIDs in yataverse** and **executions attested by
kototama receipts**. No new control plane.

```
client ──► gate (OpenAI-style HTTP, auth) ──► murakumo placement
                                                  │ job {:kind :web/extract ...}
                                                  ▼
                                            worker node (zone-labelled)
                                              fetch → extract → CID(put)
                                                  │
                          receipt (kototama) ◄────┤
                          bytes (yataverse)  ◄────┘
```

### Job kinds

| kind | input | output (all CIDv1, IPLD) | analogue |
|---|---|---|---|
| `:web/fetch` | url, headers policy, render? | raw body CID + response meta (status, final url, fetched-at, content-hash) | Firecrawl scrape (raw) |
| `:web/extract` | fetch CID, schema/format (`:markdown` `:edn` `:json-schema`) | extracted document CID | Firecrawl extract |
| `:web/crawl` | seed urls, scope rules, budget | manifest CID listing fetch/extract CIDs (a DAG) | Firecrawl crawl |
| `:web/search` | query, filters, k | ranked result list CID (url, title, snippet, source-index) | Tavily / Exa |

`crawl` is a coordinator job that fans out `fetch`/`extract` child jobs; it owns
only the frontier and budget, never the bytes.

### Search: federated first, own index later

1. **Phase 1 – federated meta-search.** `:web/search` workers run SearXNG-style
   backends (one per zone/egress IP) and return a merged list. This removes the
   single SearXNG host and spreads egress, but is still a meta-search over
   third-party engines.
2. **Phase 2 – own index.** Crawl output is chunked and embedded on nodes;
   each node holds shards of the index keyed by CID. A query fans out to shard
   holders (zone-aware via `yataverse.distribution.planner`) and merges top-k.
   Semantic (Exa-like) retrieval is only possible from here on.

### Trust and verification

- **Receipt** per job: `{job-id, kind, input-CID(s), output-CID, node-DID,
  started/finished, fetch-evidence}` signed by the node key; the client can
  check output bytes against the CID. This proves *what was returned*, not that
  the origin site served it honestly.
- **Fetch honesty** is the hard problem. Mitigation: `:web/fetch` with
  `:verify {:replicas 2}` runs on two nodes in different zones and compares
  content hashes; disagreement is returned, not silently resolved. Pages that
  legitimately vary (ads, timestamps) are compared after extraction.
- **Admission**: nodes start pending (per murakumo community enrollment); web
  jobs are only placed on admitted nodes, since they carry egress and abuse risk.

### Egress, abuse and legal controls (required before any public gate)

- Per-node egress policy in `fleet.edn` labels (e.g. `:egress "residential"`),
  never public by default.
- Honour robots.txt and per-host rate limits at the coordinator; a denied URL is
  a typed refusal, not an empty result (fail closed, matching this repo's style).
- No credentials, cookies or operator secrets in job payloads or receipts
  (RULES.md #4). Authenticated crawling is out of scope.
- Private-range and metadata-endpoint targets (SSRF) are rejected at the gate
  and again at the worker.

### Placement

Reuse `:labels {:zone ...}` and the planner: fetch jobs prefer a node near the
target when a geo hint exists; extract jobs prefer the node that already holds
the fetch CID (bitswap locality) to avoid moving bytes.

## Control plane: itonami.cloud bots (owner direction 2026-09-29)

Control is exercised by itonami.cloud bots; execution stays with murakumo workers.

| layer | owner | role |
|---|---|---|
| control (decisions) | itonami.cloud bot | choose crawl targets, cadence, budget, scope; evaluate results and propose the next plan |
| data plane | murakumo workers | run `:web/*` jobs deterministically |
| proof + storage | kototama receipt, yataverse CID | attest and keep bytes |

The bot follows the existing hyakka-style pattern (evidence script → proposal
→ gate), except that its output is a **crawl plan** (seed, scope, budget, rate
limit) submitted as `:web/crawl` jobs instead of a PR.

Rules:

1. **The bot is not in the per-request path.** It emits plans; workers execute
   them mechanically. No LLM call per fetch (latency, cost, reproducibility).
2. **Safety gates are enforced by the gate and workers, not by the bot.**
   robots.txt, SSRF rejection, rate limits and credential exclusion hold even if
   the bot is manipulated. The bot's permission table only narrows further.
3. **Fetched content is untrusted data.** Page text is never interpreted as
   instructions by the bot (prompt-injection boundary); it is passed to the bot
   as quoted evidence only.
4. **The public search/extract API is served by the gate, not by a bot.**
   External customers must not depend on a bot for availability or billing
   audit; the bot is the operator, not the front door.

Permission table sketch (`yakuwari.edn`; unlisted capabilities are `:blocked`):

```edn
{:yakuwari/capabilities
 [{:capability :read_evidence          :decision :ok}
  {:capability :propose_crawl_plan     :decision :ok}
  {:capability :submit_plan_known_host :decision :ok}      ; host already admitted, within budget
  {:capability :crawl_new_host         :decision :owner-approval}
  {:capability :raise_budget           :decision :blocked}
  {:capability :change_egress_policy   :decision :blocked}
  {:capability :authenticated_crawl    :decision :blocked}
  {:capability :publish                :decision :blocked}
  {:capability :send_outbound          :decision :blocked}]}
```

Report format keeps the measured / not-measured distinction used by the
existing bots.

## Non-goals

- Replacing the hyakka bots (they become clients of `:web/crawl` later).
- Putting an LLM/bot in the per-fetch path or in front of the public API.
- Bypassing bot detection, CAPTCHAs or paywalls.
- Claiming decentralized *search quality*; Phase 1 is federated egress only.

## Open questions (owner decisions)

1. **Gate**: extend `generation.murakumo.cloud`-style gate, or a new
   `web.murakumo.cloud`? (Recommendation: new gate; different scope/token, as the
   generation gate showed hosts are not interchangeable.)
2. **Rendering**: JS rendering needs a headless browser on nodes; Mac minis can
   host it, but it widens the attack surface. Start HTML-only?
3. **Economics**: how workers are paid/metered (relates to the AWAI billing
   model); out of this ADR.
4. **Index storage cost** for Phase 2 on 16 GiB nodes.

## Milestones

| # | deliverable | proof |
|---|---|---|
| M0 | pure spec: job schemas + receipt shape (`src/murakumo/web.cljk`), refusal cases | `test/murakumo/web_test.cljk` (9 tests / 53 assertions, kbb) — **done 2026-09-29** |
| M1 | `:web/fetch` + `:web/extract` on 2 nodes, CID out, receipt signed | **done 2026-09-29 (local store)**: worker + nbb host, run on two real fleet nodes (benjamin macOS/Node 26, gad Linux/Node 18) over BatchMode ssh; both fetched `example.com` to the same CID, receipts verify against each node's own `did:key` (ed25519). IPv4-first address fallback (gad has no IPv6 route). Bytes are stored in each node's local content-addressed store; the kotobase block put (`host/put-block!`, verify-after-put, Authorization on stdin, retry queue) is implemented and tested against a local fake server but **not run against the real gateway** (needs a minted CACAO). `:json-schema` extract is a typed refusal. |
| M2 | `:web/crawl` coordinator with robots/rate limits, budget | **done 2026-09-29**: RFC 9309 robots, per-host delay, page/depth/scope budgets, 10-minute wall-clock deadline that still stores the manifest, signed crawl receipt. Live: `example.com` from the operator machine and on benjamin via ssh. **Not done**: an owned multi-page site; parallel crawling (sequential per crawl); `:same-domain` = seed host + subdomains (no public-suffix list). |
| M3 | `:web/search` Phase 1 federated, replaces the SearXNG single host | **partial 2026-09-29**: `web/search.cljk` (10 tests / 21 assertions) fans out to operator-configured backends, drops URLs failing the policy, dedupes, merges by reciprocal-rank fusion (integer scores, deterministic CID), records per-backend failures, refuses when all fail, marks results `:untrusted`, keeps the plain query out of the receipt (hash only). `host/searxng` adapter verified end to end against **local fake SearXNG servers** only. **Not done**: run against a real SearXNG/engines; the comparison vs the current SearXNG host (the milestone's proof); multi-node fan-out with different egress/zones; SearXNG is not yet replaced. **Privacy**: the query is sent to the backends and, via them, to third-party engines. |
| M4 | dual-zone fetch verification | forced-disagreement test — **implemented and unit-tested; cannot pass live on this fleet** (2026-09-29): `web/verify.cljk` (9 tests) selects one node per distinct zone; live `web-fleet-smoke` on benjamin+gad returns `:verify/insufficient-zones {:requested 2 :available 1}` because every fleet node shares one public egress address, which is the correct refusal. Same-egress cross-node agreement was observed (`:agree`, identical hash) but is not zone verification. **Needs a node with a different egress** (VPS / cloud function in another region running the same bundle). |
| M5 | Phase 2 sharded index | recall vs Phase 1 on a fixed query set — **index done, recall not measured** (2026-09-29): `web/index.cljk` (11 tests): lexical BM25 over CID-addressed shards (FNV-1a in plain arithmetic — a JS signed-32-bit bug in the first version was caught by the published test vectors), CJK bigram tokenizer, deterministic root CID, unfetchable shard reported rather than read as "no match"; node ops `:index` / `:index-search` run live on benjamin (crawl -> index -> search). `index/recall-at-k` exists for the comparison, but there is no real query set or Phase-1 backend to compare against, and the index is **not semantic** (no embeddings). |

Dependencies: M1+ needs the yataverse murakumo-overlay transport (ADR-2607023100)
or an interim HTTP put to the existing IPFS gateway.

## Operating status (2026-09-29)

Nodes: benjamin, gad (`web-nodes.edn`, both zone "jp", one shared egress). Node
transport is one ssh per request to a bundle under `~/.murakumo-web/`; there is
no daemon to supervise. Runbook: `docs/web-plane-runbook.md`. Tests:
`scripts/web-test-all.sh`. Health: `scripts/web-fleet-health.cljk`. Bot profile:
`hermes/profiles/web-plane` (no cron registered — that is the owner's act).

## Independent review and fixes (2026-09-29)

A read-only adversarial review of the M0-M5 code found 15 defects; each was
checked and fixed with a regression test. The ones that mattered:

| finding | fix |
|---|---|
| Resolved IPv6 addresses bypassed the SSRF check (the resolver returns bare `::1`; the check only knew bracketed forms) — `attacker.example` with only an AAAA `::1` would have been fetched | `private-address?` parses IPv6 (`::`, embedded IPv4, mapped/NAT64/6to4/Teredo/ULA/link-local/doc ranges) and only allows global unicast; more IPv4 reserved ranges; octal-looking and trailing-dot hosts refused |
| `:get` / any store key was a path: `{:cid "../node.key"}` returned the node's signing seed | store keys must match a CIDv1 base32 pattern; anything else is refused on read and write |
| Index poisoning: any stored bytes were accepted as a crawl manifest | pages enter the index only with a signed `:web/fetch` receipt from this node naming exactly that page, 2xx, HTML; foreign/garbage manifests are refused |
| Redirects bypassed robots and scope | crawl fetches with `:follow-redirects? false`; each redirect target re-enters the frontier through robots/scope/dedupe |
| Robots: a `Sitemap:` line emptied our group (fail-open); partial/empty agent tokens matched us; BOM dropped the first group | RFC 9309 semantics: unknown lines ignored, exact product token, BOM stripped |
| Quadratic regexes hung `extract`/`index` on hostile pages (measured minutes) | single forward scanner with a size cap; a hostile-markup test (`<h1>` x 150000 etc.) must finish in seconds — it also caught a `last`-on-vector quadratic introduced by the rewrite |
| DNS child took the host as an argv (a host `--inspect=...` became a node option); curl globbing and proxy env not disabled; 4.6% of temp names contained `/` | host passed via environment and refused if it starts with `-`; `-g --noproxy '*'`; hex temp names; `put-block!` never throws |
| Non-2xx pages were "fetched" and indexed; empty results returned `:ok` | non-2xx skipped with a typed reason; budget counts attempts; an index that indexes nothing and a token-less query are refusals |
| Cross-host score drift from `Math.log` | `ln` from IEEE basic operations only |
| Verify accepted receipts without checking them; ssh host could start with `-` | replicas with receipts that do not verify against the node key (or name another DID) are errors; `--` before host, leading `-` refused |

A second class of bug was found only by running on the nodes, not by the tests:
`js->clj` on `process.env` broke under **stock nbb** (the engine nodes run) while
passing under kbb. `scripts/web-test-all.sh` now also runs a `:selftest` op on
the built bundle under stock nbb, and it passes on benjamin (Node 26) and gad
(Node 18) with identical CIDs.

Not fixed (accepted, documented): the deploy swap has a millisecond window with no
`bundle/`; UTF-8 replacement-character and `\s` differences between JVM and JS are
moot because the host is JS-only; the hosts assume one crawl per node at a time.
