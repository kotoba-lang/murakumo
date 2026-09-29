# Web plane runbook (ADR-260929)

Operating the distributed search / extract / crawl / index plane. Read the ADR
for the design; this is what to run.

## Shape

- **No daemon, no listener.** A node is a directory `~/.murakumo-web/` plus stock
  `nbb`. The coordinator runs one `ssh` per request (BatchMode, the same path
  fleet operations use); `web_node.cljs` reads one EDN request on stdin and prints
  one EDN line. A node opens no new port, so there is nothing to keep alive.
- **Registry**: `web-nodes.edn` (hosts + declared egress zones). No secrets.
- **Node state** (`$MURAKUMO_WEB_HOME`, default `~/.murakumo-web`):

  | path | what | mode |
  |---|---|---|
  | `bundle/` | code (replaced atomically by deploy; previous kept as `bundle.old/`) | |
  | `node.key` | ed25519 seed (hex), created on first use; the node's `did:key` derives from it | 0600 |
  | `store/` | content-addressed bytes (fetched pages, extracts, manifests, index shards) | |
  | `zone.edn` | optional `{:zone "..."}` | |
  | `backends.edn` | optional vector of `{:name :kw :base-url "http://..."}` SearXNG endpoints for `:web/search` | |
  | `kotobase.url`, `kotobase.prefix`, `kotobase.auth-cmd`, `kotobase.auth` | optional block-put config (see below) | `kotobase.auth` 0600 |
  | `pending-put.txt` | CIDs whose remote put failed, retried by `:sync` | |

## Deploy / update

```sh
scripts/web-deploy.sh benjamin      # any host in web-nodes.edn
scripts/web-deploy.sh gad
```

It builds a `.cljc` bundle (stock nbb does not load `.cljk`), verifies
`MANIFEST.sha256` on the node **before** swapping, and keeps `bundle.old/`.
Roll back: `ssh <host> 'cd ~/.murakumo-web && rm -rf bundle && mv bundle.old bundle'`.
Nothing else on the node is touched.

## Health

```sh
kbb --classpath "src:test" scripts/web-fleet-health.cljk      # exit 0 only if all healthy
python3 hermes/profiles/web-plane/scripts/web_plane_evidence.py   # same, three-valued exit for the bot
```

`healthy?` is false for: curl missing, free disk under 512 MiB, pending puts.
`:search/no-backends-configured` and `:put/no-remote-configured` are notes, not
outages (fetch/crawl/index do not need them).

## The Modal node (second egress zone)

`deploy/modal/web_node.py` runs the same bundle on Modal from a different network
(measured: Brussels, GCP). It has **no public URL**: it is called through Modal's
authenticated API with your own Modal credentials (`scripts/modal_call.py`). State
(`node.key`, `store/`) is on the Volume `murakumo-web-node-state`.

```sh
scripts/web-bundle.sh build/web-bundle
MURAKUMO_WEB_BUNDLE=build/web-bundle modal deploy deploy/modal/web_node.py   # (re)deploy
modal app stop murakumo-web-node                                             # turn it off
```

Redeploy after any change to the web sources (the bundle is baked into the image).
Cost is per invocation (CPU seconds, cold start ~ tens of seconds); nothing runs
when idle. Check its address with the `:egress` op before trusting a zone label.

## Optional configuration

- **Search backends** (operator-only; the query is sent to these and, through
  them, to third-party engines). Kinds: `:searxng` (needs `:base-url`), `:wikipedia`
  (`:lang`, default `"en"`), `:hn`:
  `echo '[{:kind :wikipedia :name :wp} {:kind :hn :name :hn}]' > ~/.murakumo-web/backends.edn`
- **yataverse / kotobase put** (files in `~/.murakumo-web/`; ssh runs a non-login
  shell so env vars are unreliable):

  | file | content |
  |---|---|
  | `kotobase.url` | gateway base URL, e.g. `https://ipfs.kotobase.net` |
  | `kotobase.prefix` | optional, default `/ipfs/` (archive plane); the datom plane is `/ipld/` |
  | `kotobase.auth-cmd` | EDN argv vector of **your minter**, e.g. `["/usr/local/bin/mint-cacao" "--aud" "ipfs.kotobase.net"]`; run without a shell **before every write**, stdout is the `Authorization` value |
  | `kotobase.auth` | a static value — only for a gateway that accepts one (`chmod 600`) |

  Why a command: kotobase authorizations are short-lived CACAOs with single-use
  nonces (`kotobase.blocks` says so), so a stored value works once at best (there
  is a test for exactly that). This code mints no credentials. Each put is sent
  as `application/vnd.ipld.raw`, then read back and its CID recomputed. A failed
  put never fails the job: the CID is queued, then `:sync` drains it.
  `:health` shows `:put/no-remote-configured` until url and one authorization
  source are present.

## Routine ops (via `murakumo.web.dispatch/call!`)

| op | when | note |
|---|---|---|
| `:health` | every check | read-only |
| `:sync` | after configuring a remote / when `pending-puts` > 0 | not auto-retried |
| `:gc {:max-bytes n}` | store approaching disk limit | **deletes oldest bytes; until the remote put is live nothing else holds them.** Owner approval (yakuwari `:run_gc`) |
| `:get {:cid}` | retrieve an artifact from a node (base64, 8 MiB cap) | |

## Failure playbook

| symptom | meaning | action |
|---|---|---|
| `:dispatch/ssh-failed` with `transient? true` | ssh could not connect (255); idempotent ops already retried once | check Tailscale/host; `ssh -o BatchMode=yes <host> true` |
| `:fetch/transport :curl/exit-7` | no connection to any vetted address | node egress/DNS problem; try another node |
| `:url/private-address` on a public name | DNS returned a private/loopback answer for that host | correct behaviour (rebinding guard) — do not bypass |
| `:robots/unreachable` skips | robots.txt returned 5xx / unreachable → host treated as fully disallowed (RFC 9309) | wait; not a bug |
| `:verify/insufficient-zones` | fewer distinct egress zones than replicas | add a node with a different egress; **do not relabel** |
| `stats :deadline-exhausted? true` | crawl hit its 10-minute wall clock; manifest was still stored | re-plan with fewer pages or run again |
| `:index/shards-unavailable` | a shard CID is not in this node's store | shards must be on the node answering the query (or fetched via `:get`) |
| `:put/verify-mismatch` | remote returned different bytes than were put | treat the remote as untrusted; investigate before syncing more |

## Known limits (honest list)

- **Fleet nodes share one egress** (Tokyo); the second zone is the Modal node.
  Zone labels must match measured egress.
- Yataverse block put is implemented against a local fake server only; the real
  gateway needs a minted Authorization.
- Index is lexical BM25 (CJK bigrams), not semantic. The recall comparison
  (`docs/evidence/web-recall-20260929.edn`) is n=24 on a closed corpus; see the ADR
  before quoting it.
- Search runs live against Wikipedia and Hacker News; a SearXNG instance has only
  been exercised as a local fake (`searxng.gftd.ai` is unreachable from here).
- JS rendering, authenticated crawling and JVM hosts are not implemented.
