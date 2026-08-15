# ADR-260815: The interconnect is measured, or the plan says it was not

- Status: accepted
- Date: 2026-08-15

## Context

`murakumo.infer.plan/choose-strategy` picks tensor, expert or pipeline
parallelism from `:link-gbps`, returning `"tensor"` at 20 Gbps and above. It
has been shipped, oracle-backed and parity-tested since W6.

Measured 2026-08-15: **nothing in this repository produces that number.** Every
caller is a test passing a literal — 1, 40, 100 — and the single production
shape is `(or link-gbps 0)`. A fleet nobody measured and a fleet measured at
0.5 Gbps arrive at the chooser as the same value.

That is the failure ADR-2608136000 (root) names: a check that could not run
returns what a check that ran and found nothing wrong returns. Here it happens
to fail safe, because 0 yields `"pipeline"` and this fleet wants pipeline
anyway. It has never been wrong. It has also never been right for a reason.

Two measurements taken the same day say why that stops being acceptable soon.

**The interconnect is 1 GbE, and that is now a measurement rather than
folklore.** Nine of eleven inventoried nodes answered; every one reports `en0
media: autoselect (1000baseT <full-duplex, ...>)`, and a verified transfer
between `simeon` and `judah` moved 67,108,864 bytes in 575 ms — **933 Mbps**,
i.e. the wire is saturated. ADR-2605300000 chose pipeline parallel for a true
reason and the reason still holds.

**The fabric that would overturn it is one purchase away.** Each Mac mini
exposes three Thunderbolt ports (`en2`/`en3`/`en4`), all three already enrolled
as members of `bridge0` by macOS default, with `bridge0 status: inactive` on
every node. Twenty-seven ports across the nine reachable nodes, zero cables.
Nothing on the node side has to change for that to become a fabric — which is
exactly why the measurement has to exist before the cable does, not after.

## Decision

Add a measurement plane that produces `:link-gbps`, and make it refuse to
produce one it did not earn.

- `kotoba/infer_topology_core.kotoba` — the decision core. Given folded
  interconnect totals it answers which of four situations we are in
  (`measured` / `partial` / `none` / `unverified`) and gates the link number:
  **zero unless every boundary the plan needs carries a verified transfer.**
- `murakumo.infer.topology` — the cljc fold, and `decide`, which returns
  `choose-strategy`'s verdict with the evidence that produced it attached.
- `murakumo.infer.topology-probe` — the nbb feed: `discover`, `nominal`,
  `thunderbolt`, `measure`.

Zero is the same conservative input the fleet already supplies, so **no plan
this fleet makes today changes.** What is added is that a caller can ask why it
got pipeline and get one of four answers instead of a zero meaning four things.

Three rules the core enforces, each of which was a bug before it was a rule:

- **A claim is not a measurement.** Interface media speed is what the NIC
  negotiated, not what two nodes achieved across a switch. It is reported and
  it cannot lift the gate. This fleet has already paid once for trusting an
  advertised capability: 44 of 45 nodes in the browser-tier registry reported a
  byte-identical memory figure that was a compiled-in constant
  (`murakumo.infer.join`, 2026-07-31).
- **A fabric is worth its weakest boundary in provenance, not only in speed.**
  One asserted hop in an otherwise measured ring makes the ring unverified.
- **Never observed is not observed as dead.** `nil` and `0 Mbps` fold
  differently: the second is a fact about a link, the first is the absence of
  one, and only the second is worth acting on.

## Evidence

The probe's first implementation piped `dd` into `nc` on both ends and reported
**9,587–14,913 Mbps across a 1 GbE fleet** — fifteen times the physical
ceiling. Measured cause: macOS `nc` abandons the connection when the send
buffer fills, so **133,120 bytes of a 16,777,216-byte transfer arrived**, the
sender exited 0, and the 8 ms it took to fail became the denominator. Sender
exit status, elapsed time, and the absence of any error all reported success.
Holding the receiving ssh session open changed nothing, which ruled out the
obvious explanation and left the real one.

The transport is now python3 (present on every node checked) and **the receiver
counts the bytes**; a boundary whose count does not match what was sent is
recorded as attempted and unverified, never as its apparent speed. `measure`
also calibrates against loopback on the first node first, so a reading bounded
by the prober rather than the wire is labelled `:method-limited` — a floor on
the link, not a measurement of it.

Both directions are exercised in `murakumo.infer-topology-test`: a claimed
40 Gbps Thunderbolt fabric must not reach `:tensor`, a half-measured fast one
must not either, and a **verified** 24 Gbps one must. A gate that only refuses
decides nothing, so the passing case is part of the gate.

`murakumo.infer-topology-kotoba-parity-test` pins the shipped KIR against a
fresh compile of the source. This is not ceremony either: during development
the source was renamed and the artifact was not regenerated, and the drift
surfaced as `value is not the declared record type` at fleet-probe time rather
than in the suite.

## Findings this produced on day one

`discover` reconciles Tailscale, Bonjour and `fleet.edn` and reports both
directions of disagreement. First run: **four online nodes absent from the
inventory** — `jacob` (a twelve-tribes name, so a mini that was never
enrolled), `main`, `main-2`, `cursor`. Nothing was missing in the other
direction. This is `murakumo.infer.join`'s 2026-07-31 finding seen from the
other side: the registry and the machines doing the work were different sets.

`measure` on the eleven inventoried nodes reported **708–950 Mbps** on the six
boundaries it could verify — a saturated 1 GbE wire, against a prober whose own
loopback ceiling measured 134,218 Mbps, so the numbers are the wire and not the
instrument. Four boundaries came back unverified: two involve `zebulun`, which
was unreachable all day, and two involve `asher` — whose failure was a probe
defect, not a fleet one. The default port was 50060, inside macOS' 49152-65535
ephemeral range, and `asher` is precisely the node `fleet.edn` already moved off
50052 because tailscaled owns persistent outbound flows there. The default is
now 40060. The collision was visible only because those boundaries were reported
as unverified instead of being given the speed at which they failed.

Re-running on 40060 confirmed the diagnosis rather than assuming it:
`benjamin → asher` went from unverified to **641 Mbps** and coverage from 6/10
to 7/10. Coverage is partial, so the gate refuses, so the fleet plans pipeline —
which is also what the measured 641-945 Mbps would have told it. The two agree
today; the point is that the output now says which one it is.

## Why coverage is partial, and what it is partial about

The three boundaries still unverified touch `zebulun` (two) and `xavier` (one).
Chasing them produced a finding about the instrument rather than the fleet, so
it is recorded here rather than fixed in passing.

**`zebulun` is not down.** From `judah` it answers ICMP 2/2 at 0.8 ms, its ARP
entry matches the MAC in `fleet.edn`, and TCP 22 accepts — `nc -z` reports
`succeeded`. What is broken is the path *from the operator*: its Tailscale peer
shows `tx 85644 rx 0` over a `tok` relay, and `zebulun.tail110d8b.ts.net` fails
MagicDNS resolution from this Mac **and from `judah`** — the same symptom
`fleet.edn` already records for `xavier`.

**And the operator cannot see the fleet LAN at all.** This machine is on Wi-Fi
(`en0`, 192.168.1.3) while the minis are wired: 192.168.1.19, .21, .22 and .24
are all 100% packet loss from here with incomplete ARP, while the minis reach
each other in under a millisecond. Every fleet operation from this vantage point
therefore rides Tailscale, and a node whose Tailscale path is broken is
invisible to the operator while remaining perfectly healthy to its peers.

That is worth stating plainly, because it is the same shape as everything else
in this ADR: **the probe measures the fleet from somewhere, and where it stands
bounds what it can learn.** The data path already avoids this — transfers run
node-to-node over `:rpc-ip` — but the control path that starts them is
operator-to-node. A peer-relayed control path would close the gap; it is not
built here because node-to-node SSH over the raw LAN has no credentials
(`Permission denied (publickey)` from `judah` to `zebulun`), and distributing
keys to fix a measurement is a change to the fleet's trust topology, not a probe
feature.

`xavier`'s host key is now verified out of band and trusted: `ssh-keyscan` over
`gad`'s wired LAN (192.168.1.28) and over the tailnet (100.87.226.80) return the
identical `SHA256:iaNh9QmKQ2ajxibicJ5XyCQPvIv7L3jghu5SWRUO+8A`. There was no
prior entry to conflict with — consistent with the JetPack reflash `fleet.edn`
dates to 2026-07-15. It still declines this session's credentials, so it stays
unmeasured.

Both gaps are operations, not defects, and the plane reports them as `:partial`
rather than as a fabric. Closing them is what turns this fleet's answer from
"pipeline, unproven" into "pipeline, measured".

## Consequences

`fleet.edn` is demoted from "what is out there" to policy — labels, roles,
credentials, exclusions. Liveness comes from the live signals, and the
disagreement between them is output rather than something silently merged.

The Thunderbolt decision now has a number attached to it. `thunderbolt` reports
27 idle ports and no active bridge; when cables arrive, `measure` is what
decides whether `:tensor` is real, and the 20 Gbps threshold in
`infer-plan-core` is untouched and unduplicated.

`measure` moves real traffic between fleet nodes and is not free; it is an
operator verb, not something a plan runs. Above roughly 10 Gbps the python
prober may become the bottleneck, in which case the boundary is reported as
`:method-limited` rather than as a speed, and `iperf3` — absent from every node
checked — is the upgrade.

Nothing here decides a strategy. It decides whether we are entitled to an
opinion about the link, and says which.
