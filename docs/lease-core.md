# The quorum lease register's decisions, in Kotoba

Tracked in [JUN-289](https://linear.app/kotobalabs/issue/JUN-289), under
ADR-2610071730 P3b. A job lease's holder and epoch, which
[agent step](agent-step.md)'s `receipt-verdict` checks, come from the quorum
lease register. Its decisions are pure, so a static-image node agent can be
both a voter and a proposer.

`kotoba/fleet_lease_core.kotoba` ports the decisions of
`src/murakumo/infer/lease.cljk` and of the Paxos acceptor in
`src/murakumo/infer/jobs.cljk`:

| function | .cljk | rule |
| --- | --- | --- |
| `majority` | `jobs/majority` | floor(n/2) + 1 |
| `ballot-ok?` | `jobs/vote` guard | a ballot is (t, id): 0 < t ≤ 2^53−1, id 1..128 bytes |
| `ballot-cmp` | `compare` on `[t id]` | t first, then the id's bytes; an absent ballot is (0, "") |
| `prepare-ok?` | `vote` "prepare" | promise a ballot not older than the current promise |
| `accept-ok?` | `vote` "accept" | accept under a ballot not older than the promise, unless that ballot already accepted a different value (even after restart) |
| `adopt?` | `jobs/adopted` | the proposer adopts the value accepted under the highest ballot |
| `lease-text` | the value map | `{class epoch node revision slot until}` as canonical JSON; values are compared by this text |
| `valid-value?` | `valid-value?` | an enabled node; class 1..128; slot 0..255; epoch ≥ 1; until ≤ now + ttl + skew |
| `base-of`, `id-of` | `base-of`, `id-of` | `l_<24 hex of SHA-256(class)>_<slot>`, then `_<epoch>` |
| `decide`, `decide-epoch` | `decide` | `lease/propose-open`, `lease/renew`, `lease/hold`, `lease/take-over` or `lease/held-by`, and the epoch a proposal carries |
| `held-now?` | `held-now?` | a holder trusts its lease only until `until − skew` |

The times are in milliseconds: a ttl of 15 minutes, renew-before 10 minutes
and a skew of 60 seconds, all as in `lease.cljk`. Safety does not depend on
clocks (Paxos), but liveness and expiry do. The transport, the voters'
persistence (written before replying) and the clock are the caller's.

## Reproduce

Run this from this repository. Choose a new task-owned directory for `G`.

```sh
AMU=/Users/junkawasaki/github/kotoba-lang/amu
K=/Users/junkawasaki/github/kotoba-lang
G=/Users/junkawasaki/github/workspaces/<agent>/<task>
mkdir -p "$G"
git -C "$AMU" show 457042ab741520980bceb5bf12970937d1bc8dba:seed/bootstrap/aarch64-linux-static-root.elf > "$G/root.elf"
chmod +x "$G/root.elf"
# Expected compiler SHA-256: 1739f52f02fc90c28a1fa4a6c3a3f780caeff481893e3a1cd35badd8c227682e
shasum -a 256 "$G/root.elf"
{
  echo '(ns lease-gate (:export [main]))'
  git -C "$K/org-nist-sha2" show ea83e70a6a8efb8fb12dd5f0031fcda35a9ec77b:kotoba/sha2/core.kotoba | sed '1,/\]))$/d'
  git -C "$K/org-ietf-jcs" show 6331a9b461f5858be484b9d6859f314f9c3a2510:kotoba/jcs/reader.kotoba | sed '1,/\]))$/d'
  sed '1,/\]))$/d' kotoba/fleet_lease_core.kotoba
  echo '(defn main [] :i64 (+ (if (test-lease-acceptor) 1 0) (+ (if (test-lease-race) 2 0) (if (test-lease-decide) 4 0))))'
} > "$G/gate.kotoba"
sed 's/(or (not (and (= bt at) (= bid aid))) (= value accepted))/true/' "$G/gate.kotoba" > "$G/reuse-ballot.kotoba"
sed 's/(not (= value "")) (>= (ballot-cmp bt bid pt pid) 0)/(not (= value "")) true/' "$G/gate.kotoba" > "$G/accept-no-promise.kotoba"
sed 's/(> now (+ v-until (lease-skew))) "lease\/take-over"/(> now v-until) "lease\/take-over"/' "$G/gate.kotoba" > "$G/no-skew.kotoba"
podman run --rm --pull never --platform linux/arm64 --network none \
  -e KEXE_CAP_RESOURCES_35=/gate -v "$G:/gate" -w /gate docker.io/library/python:3.12-alpine sh -c '
    for c in gate reuse-ballot accept-no-promise no-skew; do
      rm -f /gate/$c.elf
      ./root.elf compile /gate/$c.kotoba --target aarch64-linux --output /gate/$c.elf --format static >/dev/null || exit 1
      chmod +x /gate/$c.elf
      echo "$c $(KEXE_STRUCTURED_REPORT=1 /gate/$c.elf | cut -c1-26)"
    done'
```

The reported result is a bitmask:

| bit | group |
| --- | --- |
| 1 | `test-lease-acceptor`: majorities, ballot order, prepare and accept, including a reused ballot, and adoption |
| 2 | `test-lease-race`: two proposers over three voters (below) |
| 4 | `test-lease-decide`: open, hold, renew, held-by, take-over at the skew edge, `held-now?`, `valid-value?`, and the base and id against Python `hashlib` |

`test-lease-race` replays this race step by step:
1. Proposer A prepares (100, a) at voters 1 and 2.
2. Proposer B prepares (101, b) at voters 2 and 3.
3. A's accept succeeds only at voter 1, which is short of a majority.
4. B's accept succeeds at voters 2 and 3.
5. A retries with (102, a), sees B's value accepted at voter 2, adopts it,
   and accepts it.

What was chosen, B's lease, stays chosen.

Measured on 2026-10-23 (arm64 Linux 6.15 under podman, amu `457042ab` root):

| source | required reported result | what drops |
| --- | --- | --- |
| unchanged | 7 | nothing |
| a ballot may accept a second value | 6 | the acceptor tests |
| accept ignores the promise: A also reaches a majority, so two values are chosen | 4 | the acceptor and race tests |
| take-over at `until`, without the skew | 3 | the decide tests |

The gate image runs in under 0.01 s with a 768 KiB maximum resident set. The
receipt is [evidence/lease-core-20261023.json](evidence/lease-core-20261023.json).

## What this does not show

- The voters' transport and their persistence before replying.
- A lease register running across separate nodes.
- The parity rows were derived by hand from the `.cljk`. The `.cljk` itself
  was not run, because it needs nbb, which the workspace rule rules out.
- x86_64 execution.
