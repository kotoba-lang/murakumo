# Two itonami nodes converge on signed statements over TCP

Tracked in [JUN-287](https://linear.app/kotobalabs/issue/JUN-287), under
com-junkawasaki/root ADR-2610222140 §1. Every node computes the ledger state
from the same signed inputs, with no coordinator. The completion gate in
JUN-287 asks that two independent nodes read back the same state from the
same signed inputs. This is the first measured step of that gate. It runs
two separate processes on one loopback interface. Separate hosts and failure
domains come later (JUN-316).

`kotoba/fleet_node_peer.kotoba` is the exchange. Each node runs as its own
loaderless static image (`peer-main`) and works like this:

1. It listens on its own port, using wire 43.
2. It derives its did:key from its seed. It signs its declaration and a
   heartbeat, using [`fleet_node_enrollment`](node-enrollment.md).
3. It dials the peer (wire 45), writes one line of the ten statement fields,
   and closes the connection. While the peer is not listening yet, a 50 ms
   accept on its own listener serves as the wait.
4. It accepts the peer's connection and reads the peer's line to end of
   file.
5. It runs `signed-step` three times from -1 for both nodes, through the same
   verification. It prints `<node> <state> <declaration digest>` for both
   nodes, in node order.

L1 is a constant in this gate: both nodes are admitted with their
declaration digests. The seeds are the RFC 8032 test keys and serve this gate
only.

## Reproduce

Run this from this repository with clean pinned sources. Choose a new
task-owned directory for `G`.

The container only provides a shell. Its image is named by tag, and podman
must already have it as **linux/arm64**: `--pull never` refuses a download.
On 2026-10-23 the local `alpine:3` tag was retagged to an amd64 image by
another task, so this gate uses `python:3.12-alpine`, which is arm64. The
node images run natively either way, but `--platform` must match the image.

```sh
AMU=/Users/junkawasaki/github/kotoba-lang/amu
TS=/Users/junkawasaki/github/kotoba-lang/tsukasa
ED=/Users/junkawasaki/github/kotoba-lang/org-ietf-ed25519
SHA=/Users/junkawasaki/github/kotoba-lang/org-nist-sha2
G=/Users/junkawasaki/github/workspaces/<agent>/<task>
mkdir -p "$G"
git -C "$AMU" show 51b89255ef674a2da8bfbe650c971f1b08c0e3d3:seed/bootstrap/aarch64-linux-static-root.elf > "$G/root.elf"
chmod +x "$G/root.elf"
# Expected compiler SHA-256: 88afda8977285f3839ebad1cb785a344b296c6890f3f901746259dd478ab9fb7
shasum -a 256 "$G/root.elf"
{
  echo '(ns itonami-node-peer (:export [main]))'
  git -C "$SHA" show ea83e70a6a8efb8fb12dd5f0031fcda35a9ec77b:kotoba/sha2/core.kotoba | sed '1,/\]))$/d'
  git -C "$ED" show 6f17477b5be91a0c8c369b8d30e7e5ea0c10a8aa:kotoba/ed25519/selfhost.kotoba | sed '1,/\]))$/d'
  git -C "$TS" show 03e2322489839be2176d5c23d0ccb4120d1b0ee8:kotoba/tsukasa/ledger_core.kotoba | sed '1,/\]))$/d'
  git -C "$TS" show 03e2322489839be2176d5c23d0ccb4120d1b0ee8:kotoba/tsukasa/node_state.kotoba | sed '1,/\]))$/d'
  sed '1,/\]))$/d' kotoba/fleet_node_enrollment.kotoba
  sed '1,/\]))$/d' kotoba/fleet_node_peer.kotoba
  echo '(defn main [] :i64 (peer-main))'
} > "$G/peer.kotoba"
# node b signs its declaration with node a's key, but claims its own did
sed 's/(defn- pe-decl-seed \[role :string\] :vector-i64 (pe-seed role))/(defn- pe-decl-seed [role :string] :vector-i64 (pe-seed "a"))/' \
  "$G/peer.kotoba" > "$G/liar.kotoba"
# a node that does not check declaration signatures
sed 's/(not (arena-scope (verify? (did-key-public did) (en-bytes (declaration-text node did fd av rc)) (en-sig-bytes sig))))/false/' \
  "$G/peer.kotoba" > "$G/unchecked.kotoba"
podman run --rm --pull never --platform linux/arm64 --network none \
  -e KEXE_CAP_RESOURCES_35=/gate -v "$G:/gate" -w /gate \
  docker.io/library/python:3.12-alpine sh -c '
    for c in peer liar unchecked; do
      rm -f /gate/$c.elf
      ./root.elf compile /gate/$c.kotoba --target aarch64-linux --output /gate/$c.elf --format static >/dev/null || exit 1
      chmod +x /gate/$c.elf
    done
    N=1792500000000
    pair() {  # name image-a image-b age-b port-a port-b
      env KEXE_ALLOW=37,38,43,44,45,46,47 KEXE_CAP_RESOURCES_43=127.0.0.1:$5 KEXE_CAP_RESOURCES_45=127.0.0.1:$6 \
        /gate/$2.elf a $N $5 $6 1000 > /gate/$1-a.out 2>&1 &
      env KEXE_ALLOW=37,38,43,44,45,46,47 KEXE_CAP_RESOURCES_43=127.0.0.1:$6 KEXE_CAP_RESOURCES_45=127.0.0.1:$5 \
        /gate/$3.elf b $N $6 $5 $4 > /gate/$1-b.out 2>&1
      wait
      if cmp -s /gate/$1-a.out /gate/$1-b.out; then echo "$1 same: $(cut -d" " -f1,2 /gate/$1-a.out | tr "\n" " ")"
      else echo "$1 DIFFER: a: $(cut -d" " -f1,2 /gate/$1-a.out | tr "\n" " ") b: $(cut -d" " -f1,2 /gate/$1-b.out | tr "\n" " ")"; fi
    }
    pair honest peer peer 1000 7301 7302
    pair stale peer peer 60000 7303 7304
    pair liar peer liar 1000 7305 7306
    pair control unchecked liar 1000 7307 7308'
```

Expected output, measured on 2026-10-23 (arm64 Linux 6.15 under podman on
macOS arm64). It was measured first with amu 457042ab's root and again on
2026-10-24 with 51b89255's, which the loop below needs; the lines are the
same:

```text
honest same: b70 2 k16 2
stale same: b70 2 k16 1
liar same: b70 2 k16 -1
control DIFFER: a: b70 2 k16 2 b: b70 2 k16 -1
```

- **honest.** Both processes print the same two lines. Both nodes are
  active (`2`), and each line carries the admitted digest: b70 `9af9…8c29`,
  k16 `64f1…03fb`. The digests were computed independently with Python
  `hashlib`.
- **stale.** k16's heartbeat is 60 s old against a 30 s ttl. Both processes
  hold k16 as admitted but not active (`1`).
- **liar.** k16 signs its declaration with b70's key. Both processes refuse
  it, so k16 never enters (`-1`), including k16's own process, which runs the
  same check on itself.
- **control.** The same liar paired with a node that does not check
  declaration signatures. That node admits k16 (`2`) while k16 itself does
  not (`-1`). The two views diverge. This is what shows that the
  verification is what makes them agree.

The receipt is [evidence/node-peer-convergence-20261023.json](evidence/node-peer-convergence-20261023.json).

## A continuous loop: heartbeat loss and return

`peer-loop` runs the same exchange in rounds on the clock:
- the node's key comes from a file;
- each round signs a fresh heartbeat at wire 7's wall time, writes it to the
  peer, and accepts until the round ends;
- then both nodes' states take one tsukasa `next-state` step.

A node whose heartbeats stop keeps its last line, which ages. Once that line
is older than the ttl, the node is stale, and an active node drains (`3`). A
fresh heartbeat brings it back to active (`2`).

Each statement is verified once. A declaration is checked when it first
arrives, and a heartbeat line when it arrives, with `heartbeat-problem`
asked at the heartbeat's own time. Only its age is then checked each round,
against the clock, with `heartbeat-problem`'s bounds.

Run this after the Reproduce block, with the same `G` and root. Both nodes run 24 rounds of
2.5 s with a 15 s ttl. k16 sends nothing in rounds 6 to 14, so b70 stops
hearing it for about 25 s; k16 itself keeps listening. The control `ageless`
does not look at a heartbeat's age.

```sh
sed 's/^(defn main \[\] :i64 (peer-main))$/(defn main [] :i64 (if (= (typed-cap-call 38 :string :string "0") "loop") (peer-loop-main) (peer-main)))/' \
  "$G/peer.kotoba" > "$G/loop.kotoba"
sed 's/(>= at (- now ttl))/true/' "$G/loop.kotoba" > "$G/ageless.kotoba"
# RFC 8032 test keys 1 and 2, for this gate only
echo 9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60 > "$G/a.key"
echo 4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb > "$G/b.key"
podman run --rm --pull never --platform linux/arm64 --network none \
  -e KEXE_CAP_RESOURCES_35=/gate -v "$G:/gate" -w /gate \
  docker.io/library/python:3.12-alpine sh -c '
    for c in loop ageless; do
      rm -f /gate/$c.elf
      ./root.elf compile /gate/$c.kotoba --target aarch64-linux --output /gate/$c.elf --format static >/dev/null || exit 1
      chmod +x /gate/$c.elf
    done
    run() {  # image role port peer-port out quiet-from quiet-to
      env KEXE_ALLOW=7,35,37,38,43,44,45,46,47 KEXE_CAP_RESOURCES_35=/gate KEXE_CAP_RESOURCES_43=127.0.0.1:$3 KEXE_CAP_RESOURCES_45=127.0.0.1:$4 \
        /gate/$1.elf loop $2 /gate/$2.key $3 $4 24 2500 15000 $6 $7 > /gate/$5 2>&1
    }
    seen() {  # out node: the states the run held for node, -1 left out, repeats folded
      grep "^round" /gate/$1 | awk -v n=$2 "{ for (i = 3; i < NF; i += 2) if (\$i == n && \$(i+1) != -1) print \$(i+1) }" | uniq | tr "\n" " "
    }
    for img in loop ageless; do
      p=7311; [ $img = ageless ] && p=7313
      run $img a $p $((p+1)) $img-a.out 0 0 &
      run loop b $((p+1)) $p $img-b.out 6 15
      wait
      echo "$img: b70 holds k16 at $(seen $img-a.out k16)| b70 itself $(seen $img-a.out b70)| k16 holds b70 at $(seen $img-b.out b70)"
    done
    echo "a missing key file: $(env KEXE_ALLOW=7,35,37,38,43 KEXE_CAP_RESOURCES_35=/gate KEXE_CAP_RESOURCES_43=127.0.0.1:7315 \
      /gate/loop.elf loop a /gate/none.key 7315 7316 1 100 1000 0 0)"'
```

Expected output:

```text
loop: b70 holds k16 at 0 1 2 3 2 | b70 itself 0 1 2 | k16 holds b70 at 0 1 2
ageless: b70 holds k16 at 0 1 2 | b70 itself 0 1 2 | k16 holds b70 at 0 1 2
a missing key file: peer/key-unreadable
```

- **loop.** b70 holds k16 as active (`2`) until k16's last heartbeat is older
  than the ttl. It drains (`3`) while k16 is quiet, and is active again
  after k16's first heartbeat on return. k16, which keeps hearing b70, holds
  it active throughout, and so does b70 hold itself.
- **ageless.** A node that does not look at a heartbeat's age keeps k16
  active through the silence. This is what shows that the age check is what
  drains it.
- **A missing key file** is refused by name before the node listens.

The receipt is [evidence/node-peer-loop-20261024.json](evidence/node-peer-loop-20261024.json).

## What this does not show

- Separate hosts and failure domains (JUN-316). The loop above loses and
  regains a node's heartbeats on one host.
- A loop without end. `peer-loop` runs a set number of rounds. Each round
  keeps its lines and their hex, a few KB, so a resident agent would also
  have to release those.
- The L1 operator signature, persistence, or murakumo's existing HTTP
  observation transport.
- x86_64 execution.
