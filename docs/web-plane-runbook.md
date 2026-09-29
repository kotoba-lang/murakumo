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
  | `kotobase.url` | optional gateway base URL for the block put | |
  | `kotobase.auth` | optional pre-minted `Authorization` value for the block put | 0600 |
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

## Optional configuration

- **Search backends** (operator-only; the query is sent to these and, through
  them, to third-party engines):
  `echo '[{:name :searx :base-url "http://127.0.0.1:8080"}]' > ~/.murakumo-web/backends.edn`
- **yataverse / kotobase put**: write the gateway URL to `~/.murakumo-web/kotobase.url`
  (e.g. `https://ipfs.kotobase.net`) and the *pre-minted* Authorization value to
  `~/.murakumo-web/kotobase.auth` (`chmod 600`). `MURAKUMO_KOTOBASE_URL` /
  `MURAKUMO_KOTOBASE_AUTH` also work but ssh runs a non-login shell, so the files
  are the reliable path. Writing needs a CACAO minted by the authority; this code
  mints none. Every put is read back and its CID recomputed. A failed put never
  fails the job: the CID is queued, then `:sync` drains it. `:health` shows
  `:put/no-remote-configured` until both values are present.

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

- **One egress zone today.** benjamin and gad share a public address; dual-zone
  verification is implemented and tested but cannot pass on this fleet.
- Yataverse block put is implemented against a local fake server only; the real
  gateway needs a minted Authorization.
- Index is lexical BM25 (CJK bigrams), not semantic. Recall against Phase 1
  federated search has not been measured on a real query set.
- Search has only been exercised against local fake SearXNG servers.
- JS rendering, authenticated crawling and JVM hosts are not implemented.
