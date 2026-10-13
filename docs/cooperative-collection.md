# Cooperative collection with Itonami profiles and KUMO

Each owner admits a portable Itonami profile, and each supplier separately
admits its Murakumo host. Hosting is a paid service: one local bot inference
followed by bounded crawl/index tools. Common results are shared by CID and
the existing KUMO `settle-run` pays the supplier from a funded sponsor account.
No separate crawler token, automatic issuance or payment authority in a bot.

## Current implementation

- Itonami `collection_profile` exports reviewed SOUL plus bot/owner identity
  and the two tool names. USER/MEMORY are excluded. `collection_propose` stores
  a bounded proposal. Both require a host-configured grant bound to organization,
  account, bot and exact profile revision. This is not an automatic enrollment.
- `collection/add-campaign` admits an immutable campaign epoch with approved
  profile CIDs, seed URLs, hosts, sponsor, integer KUMO rates and total budget.
  Different participant profiles may share one campaign. URLs group by host;
  global origin locks avoid concurrent campaigns crawling the same host.
- `collection-coordinator/tick!` durably offers and starts one assignment,
  runs its profile on its host, verifies/copies outputs, updates the common
  index, then submits compensation. It returns indexed-awaiting-settlement
  until a verified finalized KUMO event is committed. Maximum 20 pages,
  depth 3, minimum 1-second delay; existing workers enforce robots and DNS/SSRF.
- `collection-host/run-bot!` runs one inference against the host's explicitly
  configured loopback model. Remote gateway inference is not credited to a
  relay host. OpenAI usage must exist; model output may choose collect/hold
  but cannot alter the plan. No LLM per fetch. Counts are host-attested, not
  independent proof of compute; the settlement authority must validate meters.
- `collection-runtime/verify-and-share!` checks admitted node signatures,
  assignment/profile/owner bindings, manifest and page CIDs, scope, counts and
  independently rebuilt index. Shared puts must return true and pass CID
  readback. A node-local or queued put alone cannot qualify shared custody.
- The common index uses existing BM25 shards. All accepted contributions in
  an epoch enter a reproducible root. Root changes use a compare-and-swap and
  cannot drop accepted IDs. Each contribution's first inclusion root is frozen
  for payment retries. A new epoch is an explicit owner-approved refresh, not
  a claim to Google-scale coverage or an incremental web-wide index.
- Durable coordinator state is a 0700 directory/0600 EDN file with atomic
  rename and fsync. One authoritative filesystem, not a distributed lock.
  Running leases never expire into automatic retries. The node also fences
  assignment IDs before inference or origin requests and caches completed
  results. `recover!` rebuilds accepted-but-unindexed work and retries payment
  without repeating inference or crawling. Crash locks require reconciliation.

## Host integration

Use the existing `hermes/profiles/web-plane` operator. Do not install a second
crawler cron. That profile's permission table narrows operation to admitted
campaigns; owner review is required for participant grants, funding and prices.

On an admitted node, install the normal `scripts/web-bundle.sh` bundle and a
host-owned `~/.murakumo-web/collector-host.edn` containing public-egress opt-in,
supplier DID, bot ID, owner DID, approved profile CIDs and `:inference` with
`:url "http://127.0.0.1:<port>/v1/chat/completions"`, `:model`, `:max-tokens`
(1–1024), and optionally `:key-env`. No job carries a credential or executable.
`collection-transport/install-profile!` checks the admitted DID/public key and
puts only a host-admitted snapshot. `run-host!` uses the existing SSH/Modal
path; `get-block!` retrieves and rehashes CID blocks. Neither collection op is
automatically retried by the transport.

The operator assembles `collection-coordinator/tick!` with:

| Adapter | Required authority/evidence |
|---|---|
| `snapshot!`, `transact!` | `collection-store/open!`; one durable coordinator |
| `now-ms` | Coordinator clock |
| `run-host!` | `collection-transport/run-host!`, approved node DID/key and transport |
| `verify-result!` | `verify-and-share!`, enrolled keys and a trusted provider-usage verifier |
| `shared-put!`, `shared-get!` | Shared Yataverse storage with actual write/readback authority |
| `settle-finalized!` | `cloud-murakumo.collection-kumo/settle-finalized!` assembled with native persistence, engi QC verification and durable KUMO commit |

The participant record binds `host-id`, `node-did`, `public-key-hex`,
`supplier-did`, `owner-did`, `bot-id`, `profile-cid`, `public-egress?`, and
`transport-config`. These are owner/supplier-reviewed inputs, never model
arguments. A campaign needs dedicated funded KUMO capacity before host grants;
the coordinator's budget reservation is not a cross-campaign money escrow.
Use the existing native funding authority to reserve capacity. Prices have no
implicit defaults. One assignment's settlement is its measured units times the
approved rate card; it may not exceed the reserved ceiling or campaign budget.

Read-only bot observation:

```sh
kbb scripts/web_collection_status.cljk
```

`not-provisioned` exits 2. An unconfigured adapter or lack of funding is a
blocker, never a paid contribution. This patch does not register a production
cron, enroll existing users, change public web origin routing, or issue KUMO.
Real multi-user participation, provider metering, Yataverse shared custody and
engi settlement remain deployment gates until assembled and witnessed live.

## Verification

```sh
kbb -M:test -n murakumo.web-collection-test -n murakumo.web-index-test -n murakumo.web-crawl-test
scripts/web-test-all.sh
```

The collection tests use two distinct owner profiles and real Ed25519 keys,
but origin responses, inference and finality adapters are controlled fixtures.
They prove local composition, index sharing, payment boundaries and crash
fencing; they do not prove physical host inference or production settlement.
