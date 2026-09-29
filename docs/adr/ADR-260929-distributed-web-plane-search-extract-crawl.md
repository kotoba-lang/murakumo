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
| M3 | `:web/search` Phase 1 federated, replaces the SearXNG single host | **done** (2026-09-29): `web/search.cljk` fuses several backends (real keyless Wikipedia and Hacker News, plus a **private SearXNG on Modal**, `deploy/modal/searxng.py`, called through Modal's authenticated API with no public URL). On the 24-query set (below), Phase 1 recall@5 is 0.54 for Wikipedia+HN, 0.67 for the SearXNG alone and **0.79 for all three federated** (MRR 0.43 / 0.65 / 0.72) — federation beats any single backend here. The old single SearXNG host (`searxng.gftd.ai`, gftd ADR-2605220300) is gone: owner note 2026-09-29, "gftd.ai は prune" — so there is nothing to compare against or replace, and the federated search plus the private SearXNG on Modal is the replacement. (It was also unreachable from the operator machine.) The private SearXNG only has the engines a datacenter address can reach. **Privacy**: the query is sent to the backends and, via them, to third parties. Evidence: `docs/evidence/web-recall-{20260929,searxng-20260929,federated-20260929}.edn`. |
| M4 | dual-zone fetch verification | forced-disagreement test — **done live 2026-09-29**: a Modal function (`deploy/modal/web_node.py`, no public URL, invoked with the operator's Modal credentials, state on a Volume) runs the same bundle and exits from **Brussels, GCP AS396982 (34.14.33.155)**; the fleet exits from **Tokyo, AS4685 (220.146.170.114)**. Verifying Tokyo+Brussels: `example.com` -> `:agree` (identical hash, both receipts authenticated); `https://ifconfig.me/ip` -> `:disagree` (each zone sees its own address) — a real, not simulated, disagreement. Unit tests cover the other verdicts. Zone names in `web-nodes.edn` are the measured egress. |
| M5 | Phase 2 sharded index | recall vs Phase 1 on a fixed query set — **measured 2026-09-29** (`docs/evidence/web-recall-20260929.edn`, queries in `web-recall-queries.edn`): 24 English Wikipedia articles crawled on benjamin and indexed (24/24 pages, none rejected); 24 descriptive queries each naming one article without its title. Phase 2 (own index): recall@1 0.83, @3 1.00, @5 1.00, MRR 0.92. Phase 1 (live Wikipedia + Hacker News search): recall@1 0.29, @3 0.54, @5 0.54, MRR 0.43. **Read this carefully**: the index searches a closed 24-page corpus that contains the answer, Phase 1 searches the open web with long natural-language queries (Wikipedia's search handles those poorly) and half its results are HN links that can never match. It shows the index works and that a crawled corpus can answer questions Phase 1 misses; it does not show the index beats web search in general. n=24. Still lexical BM25, not semantic. |

Dependencies: M1+ needs the yataverse murakumo-overlay transport (ADR-2607023100)
or an interim HTTP put to the existing IPFS gateway.

## Operating status (2026-09-29)

Nodes: benjamin and gad (Tokyo, one shared egress) and the Modal node (Brussels),
in `web-nodes.edn`. Node
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

## Correction found while wiring the real gateway (2026-09-29)

Reading `kotobase.blocks` showed the first `put-block!` design was wrong for the
real gateway: kotobase write authorizations are short-lived CACAOs with
single-use nonces, and the body is `application/vnd.ipld.raw`. A fixed
`yataverse.auth` value would work for one write and then fail with 401. Now the
node runs an operator-supplied **minter command** (`yataverse.auth-cmd`, argv, no
shell) before every write; a regression test uses a single-use-nonce fake server
to show the static value fails on the second put and the minter succeeds. The
write path remains unexercised against the real gateway — it needs the minter.

## kotobase is now yataverse (owner note 2026-09-29)

The bytes plane was renamed. Node config is `yataverse.*` (`kotobase.*` and
`MURAKUMO_KOTOBASE_*` are still read as a fallback); the gateway is
`https://ipfs.yataverse.com`. Checking it showed that `GET /ipfs/<cid>` answers
**301 to a per-CID subdomain** (`<cid>.ipfs.yataverse.com`), which the put's
read-back verification did not follow and would have failed on for every real
write. The read-back now follows up to 3 redirects, and only if the final host
shares the base host's parent domain; the authenticated PUT never follows a
redirect (regression tests for both). Reading a known block through the real
gateway works end to end. Writing still needs the operator's minter
(`yataverse.auth-cmd`); nothing has been written to the real gateway.

## The yataverse write path, probed (2026-09-29)

With the owner's permission to issue the authorization myself, the write route and
auth were probed instead of assumed:

- `PUT https://kotobase.net/ipfs/<cid>` answers 401; `PUT https://ipfs.yataverse.com/ipfs/`
  and `https://yataverse.com/ipld|ipfs/` answer 405; `kotobase.net/ipld` 308s to
  `graph.kotoba.cloud`, which does not resolve.
- A self-signed CACAO from a throwaway key (the mechanism `kotobase.live-blocks`
  used on 2026-09-06) is now refused: three scopes tried, three 401s, nothing written.
- Current auth is Authn -> Biscuit, granted only to a tenant member (`kotobase.authn`).
  Signing a throwaway `did:key` in would create an account, which is not mine to do,
  and it would hold no membership anyway.

So "issue the authorization" reduces to what a tenant admin can hand over: a
`kb_sa_...` service-account secret (or membership for a node's `did:key`). The node
now consumes that secret directly (`host/biscuit-from-service-account`, tested
against a fake Authn incl. the refusals). Until it exists, puts stay in the local
store and the queue.
