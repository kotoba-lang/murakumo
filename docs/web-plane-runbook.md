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
  | `yataverse.url`, `yataverse.prefix`, `yataverse.auth-cmd`, `yataverse.auth` | optional block-put config (see below) | `yataverse.auth` 0600 |
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

## The private SearXNG (Phase 1 backend, coordinator side)

`deploy/modal/searxng.py` runs SearXNG in-process on Modal behind the same
authenticated call path (no public URL). Use it as a backend with
`{:kind :modal-searxng :name :searxng}` in coordinator-side runs (nodes have no
Modal credentials). `modal app stop murakumo-searxng` turns it off. Datacenter
addresses are rate-limited by some engines: the function returns per-engine
counts and unresponsive engines so that shows up rather than being hidden.

## Naming

kotobase was renamed yataverse (owner note 2026-09-29). Config files are now
`yataverse.*`; the old `kotobase.*` names and `MURAKUMO_KOTOBASE_*` variables are
still read as a fallback. Some source comments and the store-level docs still say
kotobase.

## Optional configuration

- **Search backends** (operator-only; the query is sent to these and, through
  them, to third-party engines). Kinds: `:searxng` (needs `:base-url`), `:wikipedia`
  (`:lang`, default `"en"`), `:hn`:
  `echo '[{:kind :wikipedia :name :wp} {:kind :hn :name :hn}]' > ~/.murakumo-web/backends.edn`
- **yataverse put** (files in `~/.murakumo-web/`; ssh runs a non-login shell so
  env vars are unreliable). The write route, probed 2026-09-29, is
  `PUT https://kotobase.net/ipfs/<cid>` (401 without a valid credential); the
  read side is `https://ipfs.yataverse.com/ipfs/<cid>` (301 to the per-CID
  subdomain, which the read-back follows). Other candidates were 405 or did not resolve.

  | file | content |
  |---|---|
  | `yataverse.url` | write base URL: `https://kotobase.net` |
  | `yataverse.prefix` | `/ipfs/` (default) |
  | `yataverse.authn.edn` | `{:tenant-id "t_..." :storage "..." :permissions [...]}` |
  | `yataverse.sa-token` | a **tenant service-account secret** `kb_sa_...` issued by a tenant admin (`chmod 600`) |
  | `yataverse.auth-cmd` | alternative: EDN argv of your own minter, run per write |
  | `yataverse.auth` | alternative: a static value, only for a gateway that accepts one |

  Current auth (superproject ADR-2609241800): a CACAO only proves who signed and
  authorizes nothing; writes carry `Authorization: Biscuit ...`, a 15-minute token
  Authn issues **only to a member of a tenant**. With `authn.edn` + `sa-token` the node
  exchanges the secret at `auth.kotoba.cloud` for a Biscuit (cached until 60 s before
  expiry) and sends it with each put. The secret is sent only to `auth.kotoba.cloud`,
  on curl's stdin, never in argv. **Self-signed CACAOs are refused** (three attempts on
  2026-09-29, all 401, nothing written), and a fresh `did:key` has no membership, so a
  credential cannot be minted from nothing — a tenant admin has to issue the secret
  (or add a node's `did:key`, shown by `:whoami`, as a member).
  Each put is `application/vnd.ipld.raw`, read back and its CID recomputed. A failed
  put never fails the job: the CID is queued, then `:sync` drains it. `:health`
  shows `:put/no-remote-configured` until url and one authorization source exist.

## Automatic provisioning via MCP / agent

Nothing can mint a tenant service-account secret except a tenant admin, so the
credential itself is a human step. Everything after it is automatic:

1. The admin gives the operator a `kb_sa_...` secret and the tenant/storage ids.
2. The operator puts them in ONE file, `~/.murakumo-web-provision/yataverse.edn`
   (directory 0700, file 0600), as a map of config-file name to content:
   `{"yataverse.url" "https://kotobase.net"
     "yataverse.authn.edn" "{:tenant-id \"t_...\" :storage \"...\"}"
     "yataverse.sa-token" "kb_sa_..."}`
3. An agent (or you) calls the MCP tool **`web_provision`** (or
   `kbb ... scripts/web-cli.cljk provision`). It refuses a drop-file that is not 0600,
   validates every name and value shape, writes each node's 0600 config (ssh stdin /
   Modal's authenticated API), and returns file names only. Then `web_config_status`,
   `web_sync`, `web_health`.
4. Delete the drop-file.

The MCP server (`scripts/web_mcp.mjs`, project `.mcp.json`, or
`claude mcp add murakumo-web -- node scripts/web_mcp.mjs`) exposes `web_health`,
`web_config_status`, `web_fetch`, `web_crawl`, `web_search`, `web_verify_fetch`,
`web_sync`, `web_provision`. Any argument named like a credential is refused, and no tool
result contains a secret. The `web-plane` bot profile may call these; see its `yakuwari.edn`.

## Public gate: web.murakumo.cloud (owner decisions 2026-10-10)

```
caller --mk1 (scope web)--> murakumo-web Worker (network-awai/cloud-murakumo, wrangler.web.jsonc)
          stateless: token, scope, body cap        |
                                                   | Workers VPC "murakumo-web-origin"
                                                   | (comfyui-gad Tunnel c75a3e83 -> 192.168.1.16:8095)
                                                   v
          web origin on gad (scripts/web_origin.cljc, systemd murakumo-web-origin, user gad)
          queue + per-subject quota + full URL policy, one job at a time
                                                   |
                                                   v  local node op (web_node.cljs, user gad)
          /home/gad/.murakumo-web  (its own node.key / did:key, store/, backends.edn)
```

- **Address**: the Tunnel has connectors on several LAN hosts, so the VPC service names
  gad's LAN address and `origin.edn` sets `:host "192.168.1.16"` (with 127.0.0.1, 6 of 20
  requests reached the origin, 2026-10-11). The origin bearer is still required for
  every request, including from the LAN.
- **No Cloudflare state** (root ADR-2609132007, no R2 since 2026-10-07): the queue,
  records and quota are files under `/home/gad/.murakumo-web-origin/` (`jobs/<id>.edn`,
  `quota.edn`). A job that was running when the origin restarted is marked failed, never
  re-run (it touched third parties).
- **Egress opt-in**: a node runs public jobs only with `:public-egress? true` in
  `origin.edn`. Today only gad's local node (user `gad`, never root) is opted in; it
  exits from the Tokyo home line. To stop public egress, set it to false (or stop the
  unit): new jobs then get `503 no_public_node`.
- **Limits** (`murakumo.web.origin/public-limits`, `default-quota`): crawl <= 20 pages,
  depth <= 3, delay >= 1 s, <= 5 seeds; search k <= 20; 200 jobs and 10 crawls per token
  subject per UTC day; queue <= 100.

API (all JSON; `Authorization: Bearer mk1...`, scope `web` or `all`):

| call | body / result |
|---|---|
| `POST /v1/web/jobs` | `{"kind":"scrape","url":...}` / `{"kind":"crawl","seeds":[...],"max_pages":n,"max_depth":n,"scope":"same-host"}` / `{"kind":"search","query":...,"k":n}` -> `202 {"id","status":"queued"|"running"}` |
| `GET /v1/web/jobs/{id}` | `{"status":"done","result":{...,"receipts":[...],"untrusted":true}}` or `"failed"` with `error`; only the token subject that created it can read it |
| `GET /` | discovery document |
| `GET /health` | origin health (no token needed at the Worker; the Worker itself authenticates to the origin) |

Errors: 400 invalid_request (with reasons), 401 invalid_token, 403 insufficient_scope,
413 too_large, 429 quota, 502 origin_unavailable, 503 gate_not_configured / no_public_node /
queue_full.

Issue a token (the Worker's own signing secret, macOS Keychain item
`murakumo-web-token-secret` on the operator Mac; never the api./generation. secrets):

```sh
cd network-awai/cloud-murakumo
MURAKUMO_TOKEN_SECRET="$(security find-generic-password -s murakumo-web-token-secret -w)" \
  kbb -M:token issue <subject> web <ttl-seconds>
```

Install / update the origin on gad (bundle under the `gad` user, then restart):

```sh
scripts/web-bundle.sh /tmp/wb/bundle
tar -C /tmp/wb -cf - bundle | ssh gad 'D=/home/gad/.murakumo-web; rm -rf $D/bundle.new && mkdir $D/bundle.new && tar -C $D/bundle.new --strip-components=1 -xf - && (cd $D/bundle.new && sha256sum -c MANIFEST.sha256 >/dev/null) && rm -rf $D/bundle.old && mv $D/bundle $D/bundle.old && mv $D/bundle.new $D/bundle && chown -R gad:gad $D && systemctl restart murakumo-web-origin'
```

The unit is `deploy/systemd/murakumo-web-origin.service`. `origin-token` (0600, >= 32 chars)
is the Worker secret `WEB_ORIGIN_TOKEN`; rotate both together.

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
