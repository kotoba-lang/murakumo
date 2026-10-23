# The topology replicated between nodes

Tracked in [JUN-289](https://linear.app/kotobalabs/issue/JUN-289). JUN-290
retires the nbb path. A resident keeps the operator-signed topology current
with `src/murakumo/infer/replicated_topology.cljk` `start!`:
- **Serving.** It serves the topology at `/topology` behind the fleet token.
- **Polling.** It fetches `/topology` from peers.
- **Accepting.** It adopts what `accept!` allows and persists it.

`kotoba/fleet_topology_replica.kotoba` does the same in a loaderless static
image, writing the file that the voters already follow
([lease node, Rotation](lease-node.md#rotation)).

- **Start.** The replica verifies its file with `topology-verdict`, which
  includes `validate!` ([topology verify](topology-verify.md)). It starts
  only on `topology/ok`, and only with a fleet token of at least 32 bytes.
- **Serve.** A `GET /topology` carrying `authorization: Bearer <token>` is
  answered 200 with the document held. As `resident.cljk` orders it, the
  token comes first:
  - any request without the token gets 401, and the replica logs it;
  - any other request gets 404.
  
  Replies are HTTP/1.0, then the connection closes. Requests are parsed as
  bytes and never decoded.
- **Poll.** Every `<poll ms>` (a request served counts as one 100 ms
  slice), the replica picks its peers as `tick!` does:
  1. the seeds given on the command line, then the url of every gateway and
     node of the payload it holds, without repeats and without its own
     address;
  2. `<fanout>` of those, starting at a cursor that moves by one each poll.

  It asks each for `/topology` with the token, reading at most 262144 body
  bytes within 3 s. It then applies `accept!`:
  - a document that verifies, advances the revision and keeps every node's
    `owner-id` is adopted and written to the file;
  - one that verifies at the same revision but with another payload is a
    conflict, recorded in `<file>.conflict`;
  - one that fails verification or replaces an owner is kept out and logged;
  - an older or equal revision is ignored.
- **Memory.** Every request and every poll runs in `arena-scope`, so only an
  adopted document stays allocated.

The command line is `replica <file> <trust> <threshold> <token file>
<listen ip:port> <poll ms> <polls> <fanout> [<seed ip:port>…]`. The replica
exits after `<polls>` polls. Its dial grant must name the seeds and the
payload's addresses.

## Reproduce

Run this from this repository with clean pinned sources. Choose a new
task-owned directory for `G`. The shell container must already be a local
**linux/arm64** image (`--pull never`). The test peers are Python, and stand
in for hostile or outdated nodes. They are not part of any node path.

```sh
AMU=/Users/junkawasaki/github/kotoba-lang/amu
K=/Users/junkawasaki/github/kotoba-lang
G=/Users/junkawasaki/github/workspaces/<agent>/<task>
mkdir -p "$G"
git -C "$AMU" show 51b89255ef674a2da8bfbe650c971f1b08c0e3d3:seed/bootstrap/aarch64-linux-static-root.elf > "$G/root.elf"
chmod +x "$G/root.elf"
# Expected compiler SHA-256: 88afda8977285f3839ebad1cb785a344b296c6890f3f901746259dd478ab9fb7
shasum -a 256 "$G/root.elf"
pin() { git -C "$K/$1" show "$2:$3" | sed '1,/\]))$/d'; }
{
  echo '(ns itonami-topology-replica (:export [main]))'
  pin org-nist-sha2 ea83e70a6a8efb8fb12dd5f0031fcda35a9ec77b kotoba/sha2/core.kotoba
  pin org-ietf-ed25519 6f17477b5be91a0c8c369b8d30e7e5ea0c10a8aa kotoba/ed25519/selfhost.kotoba
  pin org-ietf-jcs 6331a9b461f5858be484b9d6859f314f9c3a2510 kotoba/jcs/reader.kotoba
  pin tsukasa 03e2322489839be2176d5c23d0ccb4120d1b0ee8 kotoba/tsukasa/ledger_core.kotoba
  pin tsukasa 03e2322489839be2176d5c23d0ccb4120d1b0ee8 kotoba/tsukasa/node_state.kotoba
  for f in fleet_node_enrollment fleet_intent_core fleet_topology_verify fleet_lease_core fleet_lease_node fleet_topology_replica; do
    sed '1,/\]))$/d' kotoba/$f.kotoba
  done
  echo '(defn main [] :i64 (if (= (typed-cap-call 38 :string :string "0") "replica") (replica-main) (lease-node-main)))'
} > "$G/node.kotoba"
# controls: a replica that adopts whatever a peer answers, and one that serves without the token
sed 's/(let \[v (topology-verdict d trust threshold (topology-revision held))\]/(let [v "topology\/ok"]/' "$G/node.kotoba" > "$G/unchecked.kotoba"
sed 's/(or (< e0 0) (not (tr-authorized? req n token)))/(< e0 0)/' "$G/node.kotoba" > "$G/open.kotoba"
python3 -I docs/evidence/lease-membership-topology-20261023.py "$G"   # topology.json: revision 7, b70 enabled, k16 not
python3 -I docs/evidence/lease-rotation-topology-20261023.py "$G"     # revisions 6, 8, a forged 9, a 9 that replaces b70's owner
python3 -I docs/evidence/topology-replica-docs-20261023.py "$G"       # a signed revision 7 with another payload
cp docs/evidence/topology-replica-peer-20261023.py "$G/peer.py"
python3 -I - "$G" <<'PY'
import hashlib, sys
g = sys.argv[1]
for name in ["fleet", "other"]:   # test tokens, for this gate only
    open(f"{g}/{name}.token", "w").write(hashlib.sha256(f"topology-replica gate {name} token".encode()).hexdigest() + "\n")
open(f"{g}/short.token", "w").write("short\n")
PY
cat > "$G/replica.sh" <<'EOF'
TRUST=op1=did:key:z6MktwupdmLXVVqTzCw4i46r4uGyosGXRnR3XjN4Zq7oMMsw
N=1792500000000; U=1792500900000
VK='{"class":"qwen-text","epoch":1,"node":"k16","revision":7,"slot":0,"until":'$U'}'
rep() {  # image name file token listen polls peer...
  img=$1; name=$2; file=$3; tok=$4; listen=$5; polls=$6; shift 6
  dial=$(echo "$@" | tr ' ' ','),100.64.0.10:8796,100.64.0.1:8796   # the seeds, and the payload's addresses (unreachable here)
  env KEXE_ALLOW=35,37,38,43,44,45,46,47 KEXE_CAP_RESOURCES_35=/gate KEXE_CAP_RESOURCES_43=127.0.0.1:$listen KEXE_CAP_RESOURCES_45=$dial \
    /gate/$img.elf replica /gate/$file $TRUST 1 /gate/$tok 127.0.0.1:$listen 500 $polls 8 "$@" > /gate/$name.log 2>&1 &
}
ready() {  # port...: wait, at most 30 s in all, until each voter answers; the probe is prepare 0 (no ballot), which changes nothing
  python3 -I -c 'import socket, sys, time
end = time.time() + 30
for p in sys.argv[1:]:
    while time.time() < end:
        try:
            c = socket.create_connection(("127.0.0.1", int(p)), 0.5); c.settimeout(5); c.sendall(b"prepare 0 probe\n"); c.recv(4096); c.close(); break
        except OSError:
            time.sleep(0.2)' "$@"
}
rev() { python3 -I -c 'import json,sys; print(json.load(open(sys.argv[1]))["payload"]["revision"])' /gate/$1; }
for img in node unchecked open; do
  echo "== replicas: $img"
  rm -f /gate/rot-v*.state /gate/r*.json.conflict
  cp /gate/topology-r8.json /gate/r1.json; for r in r2 r3 r4; do cp /gate/topology.json /gate/$r.json; done
  # voters following r2.json, as in the lease node's Rotation
  for v in 1 2 3; do
    env KEXE_ALLOW=35,37,38,43,44,46,47 KEXE_CAP_RESOURCES_35=/gate KEXE_CAP_RESOURCES_43=127.0.0.1:782$v \
      /gate/node.elf voter 782$v /gate/rot-v$v.state 20000 /gate/r2.json $TRUST 1 $N > /gate/voter-v$v.log 2>&1 &
  done
  # peers that answer anyone: a forged 9, an older 6, a 9 that replaces b70's owner, a conflicting 7
  python3 -I /gate/peer.py 7831 /gate/topology-r9-forged.json 20 & python3 -I /gate/peer.py 7832 /gate/topology-r6.json 20 &
  python3 -I /gate/peer.py 7833 /gate/topology-r9-owner.json 20 & python3 -I /gate/peer.py 7834 /gate/topology-r7-conflict.json 20 &
  ready 7821 7822 7823; sleep 1   # the voters verify the topology before they listen
  R1=node; R3=node; [ $img = open ] && R1=open; [ $img = unchecked ] && R3=unchecked
  rep $R1 r1 r1.json fleet.token 7811 12 127.0.0.1:7812
  rep node r2 r2.json fleet.token 7812 4 127.0.0.1:7811
  rep $R3 r3 r3.json fleet.token 7813 2 127.0.0.1:7831 127.0.0.1:7832 127.0.0.1:7833 127.0.0.1:7834
  rep node r4 r4.json other.token 7814 2 127.0.0.1:7811
  # until r2 holds revision 8 (at most 60 s), then 4 s for the voters, which look at the file every 500 ms
  # (their log is buffered until they exit, so it cannot tell when they adopted it)
  for i in $(seq 1 120); do [ "$(rev r2.json)" = 8 ] && break; sleep 0.5; done; sleep 4
  env KEXE_ALLOW=7,23,37,38,45,46,47 KEXE_CAP_RESOURCES_45=127.0.0.1:7821,127.0.0.1:7822,127.0.0.1:7823 \
    /gate/node.elf proposer full 101 k16 "$VK" 7821 7822 7823 2>&1 | grep -v '^{:status' | sed 's/^/  k16 proposes: /' | cut -c1-90
  wait
  for r in r1 r2 r3 r4; do echo "  $r holds revision $(rev $r.json)$([ -f /gate/$r.json.conflict ] && echo ", conflict recorded")"; sed "s/^/    $r log: /" /gate/$r.log; done
  sed 's/^/    voter 1 log: /' /gate/voter-v1.log
done
rep node short topology.json short.token 7815 1 127.0.0.1:7811; wait
echo "a replica with a short token: $(head -1 /gate/short.log)"
EOF
podman run --rm --pull never --platform linux/arm64 --network none \
  -e KEXE_CAP_RESOURCES_35=/gate -v "$G:/gate" -w /gate docker.io/library/python:3.12-alpine sh -c '
    for c in node unchecked open; do
      rm -f /gate/$c.elf
      ./root.elf compile /gate/$c.kotoba --target aarch64-linux --output /gate/$c.elf --format static >/dev/null || exit 1
      chmod +x /gate/$c.elf
    done
    sh /gate/replica.sh'
```

Expected output, measured on 2026-10-23 (`…` is the rest of the lease):

```text
== replicas: node
  k16 proposes: chosen {"class":"qwen-text","epoch":1,"node":"k16",…}
  r1 holds revision 8
    r1 log: replica refused unauthenticated
    r1 log: replica refused unauthenticated
  r2 holds revision 8
    r2 log: replica topology 8 from 127.0.0.1:7811
  r3 holds revision 7, conflict recorded
    r3 log: replica kept topology/owner-replaced from 127.0.0.1:7833
    r3 log: replica conflict 7 from 127.0.0.1:7834
    r3 log: replica kept topology/needs-signatures from 127.0.0.1:7831
    r3 log: replica kept topology/owner-replaced from 127.0.0.1:7833
    r3 log: replica conflict 7 from 127.0.0.1:7834
    r3 log: replica kept topology/needs-signatures from 127.0.0.1:7831
  r4 holds revision 7
    voter 1 log: voter topology 8
== replicas: unchecked
  k16 proposes: chosen {"class":"qwen-text","epoch":1,"node":"k16",…}
  r1 holds revision 8
    r1 log: replica refused unauthenticated
    r1 log: replica refused unauthenticated
  r2 holds revision 8
    r2 log: replica topology 8 from 127.0.0.1:7811
  r3 holds revision 6
    r3 log: replica topology 6 from 127.0.0.1:7832
    r3 log: replica kept topology/owner-replaced from 127.0.0.1:7833
    r3 log: replica topology 7 from 127.0.0.1:7834
    r3 log: replica topology 9 from 127.0.0.1:7831
    r3 log: replica kept topology/owner-replaced from 127.0.0.1:7833
    r3 log: replica topology 7 from 127.0.0.1:7834
    r3 log: replica topology 9 from 127.0.0.1:7831
    r3 log: replica topology 6 from 127.0.0.1:7832
  r4 holds revision 7
    voter 1 log: voter topology 8
== replicas: open
  k16 proposes: chosen {"class":"qwen-text","epoch":1,"node":"k16",…}
  r1 holds revision 8
  r2 holds revision 8
    r2 log: replica topology 8 from 127.0.0.1:7811
  r3 holds revision 7, conflict recorded
    r3 log: replica kept topology/owner-replaced from 127.0.0.1:7833
    r3 log: replica conflict 7 from 127.0.0.1:7834
    r3 log: replica kept topology/needs-signatures from 127.0.0.1:7831
    r3 log: replica kept topology/owner-replaced from 127.0.0.1:7833
    r3 log: replica conflict 7 from 127.0.0.1:7834
    r3 log: replica kept topology/needs-signatures from 127.0.0.1:7831
  r4 holds revision 8
    r4 log: replica topology 8 from 127.0.0.1:7811
    voter 1 log: voter topology 8
a replica with a short token: replica token/missing-or-short
```

- **The chain.** `r2` fetches revision 8 from `r1` with the fleet token,
  verifies it and writes it to `r2.json`. The voters following that file
  adopt it, so k16, enabled by revision 8, is chosen.
- **Peers that answer anyone.** `r3` keeps the forged 9 and the 9 that
  replaces b70's owner out, and logs both. It ignores the older 6, and
  records the signed 7 with another payload as a conflict. Each poll asks
  every peer (fanout 8), starting at the cursor. The payload's own addresses
  are unreachable in this container.
- **The token.** `r4` holds another token, so `r1` answers 401 and logs it,
  and `r4` stays at revision 7.
- **`unchecked`.** A replica that adopts whatever a peer answers takes the
  older 6, the conflicting 7 and the forged 9 in turn, and ends at 6. Only the
  owner rule, checked separately, still holds.
- **`open`.** A replica that serves without the token gives revision 8 to
  `r4`, which holds another token.

The voters verify what they read on their own, so a replica that writes a
bad file still cannot make them accept it ([lease node, Rotation](lease-node.md#rotation)).
The receipt is [evidence/topology-replica-20261023.json](evidence/topology-replica-20261023.json).

## Peers from the topology

Here each replica runs in its own container on a podman network with
tailnet addresses, so the payload's urls are real and `validate!` accepts
them:
- **n1** holds revision 8 at `100.64.9.11:8796`, which is also the
  gateway's url.
- **n3 and n4** serve revision 7 at `.13` and `.14`. They poll only once, at
  the end.
- **n2**, at `.12`, holds revision 7. It has no seeds and fanout 2, so it
  finds n1 only through the payload.

Without its own address, n2's peers are n1, n3 and n4:
- **Poll 1** starts at the second of them and asks n3 and n4, which have
  nothing newer.
- **Poll 2** asks n4 and n1, and adopts revision 8.

The control `seeds-only` ignores the payload's urls.

With the same `$G`, `$K` and `node.kotoba`:

```sh
sed 's/(tr-without (tr-add-urls (tr-add-urls s0 held (json-member held p "gateways")) held (json-member held p "nodes")) self)/(tr-without s0 self)/' \
  "$G/node.kotoba" > "$G/seeds-only.kotoba"
podman run --rm --pull never --platform linux/arm64 --network none \
  -e KEXE_CAP_RESOURCES_35=/gate -v "$G:/gate" -w /gate docker.io/library/python:3.12-alpine sh -c '
    rm -f /gate/seeds-only.elf
    ./root.elf compile /gate/seeds-only.kotoba --target aarch64-linux --output /gate/seeds-only.elf --format static >/dev/null || exit 1
    chmod +x /gate/seeds-only.elf'
TRUST=op1=did:key:z6MktwupdmLXVVqTzCw4i46r4uGyosGXRnR3XjN4Zq7oMMsw
podman network create --subnet 100.64.9.0/24 itonami-replica-gate >/dev/null
node() {  # image name ip poll-ms polls
  podman run --rm --pull never --platform linux/arm64 --network itonami-replica-gate --ip $3 -v "$G:/gate" \
    -e KEXE_ALLOW=35,37,38,43,44,45,46,47 -e KEXE_CAP_RESOURCES_35=/gate -e KEXE_CAP_RESOURCES_43=$3:8796 \
    -e KEXE_CAP_RESOURCES_45=100.64.9.11:8796,100.64.9.12:8796,100.64.9.13:8796,100.64.9.14:8796 \
    docker.io/library/python:3.12-alpine /gate/$1.elf replica /gate/$2.json $TRUST 1 /gate/fleet.token $3:8796 $4 $5 2 \
    > "$G/$2.log" 2>&1 &
}
for run in "node 1" "node 2" "seeds-only 2"; do
  set -- $run
  cp "$G/topology-net8.json" "$G/n1.json"; for n in n2 n3 n4; do cp "$G/topology-net7.json" "$G/$n.json"; done
  node node n1 100.64.9.11 8000 1; node node n3 100.64.9.13 8000 1; node node n4 100.64.9.14 8000 1
  sleep 3
  node $1 n2 100.64.9.12 500 $2
  wait
  echo "n2 ($1, $2 polls) holds revision $(python3 -I -c 'import json,sys; print(json.load(open(sys.argv[1]))["payload"]["revision"])' "$G/n2.json")"
  sed 's/^/  n2 log: /' "$G/n2.log"
done
podman network rm itonami-replica-gate >/dev/null
```

Expected output, measured on 2026-10-23:

```text
n2 (node, 1 polls) holds revision 7
n2 (node, 2 polls) holds revision 8
  n2 log: replica topology 8 from 100.64.9.11:8796
n2 (seeds-only, 2 polls) holds revision 7
```

The cursor makes the second poll reach n1, and the payload's urls are what
lead n2 there: without them, n2 has no peer to ask.
The receipt is [evidence/topology-replica-peers-20261023.json](evidence/topology-replica-peers-20261023.json).

## What this does not show

- **A poll interval measured by a clock.** It is counted in 100 ms slices,
  and a served request counts as one. Wire 7 now has a clock; the replica
  does not use it yet.
- **Separate hosts and x86_64.** The containers have their own network
  namespaces and addresses, but share one machine.
