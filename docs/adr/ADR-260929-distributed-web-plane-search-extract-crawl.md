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
| M1 | `:web/fetch` + `:web/extract` on 2 nodes, CID out, receipt signed | **partial 2026-09-29**: worker (`src/murakumo/web/worker.cljk`, 12 tests / 33 assertions) + nbb host (`web/host.cljk`, curl pinned to the vetted IP, redirects re-validated per hop, local CID store, ed25519 receipt signature verified) run live against a public URL on the operator machine. **Not done**: run on two fleet nodes; yataverse/IPFS put (local dir store only); `:json-schema` extract (typed refusal); JVM host. |
| M2 | `:web/crawl` coordinator with robots/rate limits, budget | **done 2026-09-29** (`web/crawl.cljk`, `web/robots.cljk`; 10 + 6 tests, 35 + 19 assertions): RFC 9309 robots (longest match, allow wins ties, `*`/`$`, crawl-delay; 4xx = allow, 5xx/unreachable = disallow all), per-host delay = max(plan, crawl-delay), page/depth budget, scope filter, manifest CID + signed crawl receipt. Live run on `example.com` from the operator machine only; **not** run against an owned multi-page site or on a fleet node. Sequential per crawl; `:same-domain` = seed host and its subdomains (no public-suffix list). |
| M3 | `:web/search` Phase 1 federated, replaces the SearXNG single host | **partial 2026-09-29**: `web/search.cljk` (10 tests / 21 assertions) fans out to operator-configured backends, drops URLs failing the policy, dedupes, merges by reciprocal-rank fusion (integer scores, deterministic CID), records per-backend failures, refuses when all fail, marks results `:untrusted`, keeps the plain query out of the receipt (hash only). `host/searxng` adapter verified end to end against **local fake SearXNG servers** only. **Not done**: run against a real SearXNG/engines; the comparison vs the current SearXNG host (the milestone's proof); multi-node fan-out with different egress/zones; SearXNG is not yet replaced. **Privacy**: the query is sent to the backends and, via them, to third-party engines. |
| M4 | dual-zone fetch verification | forced-disagreement test |
| M5 | Phase 2 sharded index | recall vs Phase 1 on a fixed query set |

Dependencies: M1+ needs the yataverse murakumo-overlay transport (ADR-2607023100)
or an interim HTTP put to the existing IPFS gateway.
