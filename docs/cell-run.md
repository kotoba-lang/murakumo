# Itonami cell run: launch an admitted cell and sign its receipt

Tracked in [JUN-288](https://linear.app/kotobalabs/issue/JUN-288), under
com-junkawasaki/root ADR-2610222140 §3, steps 4 to 7. The acceptance there
names one OCI artifact holding a loaderless static image built by Amu
selfhost. It must:
- start on a node after its digest is checked;
- return a signed receipt;
- be refused, with a reason, on a node that does not declare or grant its
  class;
- never start when its digest does not match.

This gate does exactly that on one node.

`kotoba/fleet_cell_run.kotoba` is the node agent's run, one static image:

1. Read the node's Ed25519 seed from its key file (wire 35).
2. Sign its own declaration and heartbeat, and derive its ledger state with
   `signed-step` against the L1 admitted digest
   ([node enrollment](node-enrollment.md)).
3. Read the release's image index and decide with `launch-verdict`
   ([cell launch](cell-launch.md)):
   - the node is active;
   - SHA-256 of the bytes is the L1 cell digest;
   - the bytes are strict JSON;
   - it is an itonami index whose class annotation is the L1 class;
   - the host policy allows the class.
4. Check the image itself with `layout-verdict`
   (`kotoba/fleet_oci_layout.kotoba`). The image is the OCI image layout
   directory that `podman save --format oci-dir` writes. The layout's
   `index.json` must list exactly the manifest the release names, at its
   size. The manifest, then its config, then every layer must exist, with
   the descriptor's size and SHA-256, all hashed in the guest.
5. Start that same directory with wire 20, which may run only the granted
   `/usr/bin/podman`, with a fixed argv: `run --rm --pull never --network
   none --read-only oci:<layout>`. What runs is what was checked. The
   image's own entry runs. Stdin is at end of file, and stdout comes back
   (at most 16 KiB, within 10 s). A refused cell starts nothing.
6. Write the receipt in canonical JSON. `jcs.reader`'s `json-canonical?`
   holds for it. It is signed with the node's key (`sign-statement`):
   `{"at":ms,"cell":<index CID>,"did":..,"exit":n,"kind":"itonami.receipt/1","node":..,"output":<CID of stdout>}`.

The cell is `kotoba` source, about two lines, compiled by the same Amu root
and put in a `FROM scratch` image. A second cell, the "impostor", prints a
different line and is used only to build the swapped layout.

## Reproduce

This needs a Linux host with rootless podman. On macOS that host is the
podman machine: the agent runs inside it through `podman machine ssh`, and
the machine mounts `/Users`. Run the commands from this repository with
clean pinned sources. Choose a new task-owned directory for `G`.

```sh
AMU=/Users/junkawasaki/github/kotoba-lang/amu
K=/Users/junkawasaki/github/kotoba-lang
G=/Users/junkawasaki/github/workspaces/<agent>/<task>
mkdir -p "$G"
git -C "$AMU" show 51b89255ef674a2da8bfbe650c971f1b08c0e3d3:seed/bootstrap/aarch64-linux-static-root.elf > "$G/root.elf"
chmod +x "$G/root.elf"
# Expected compiler SHA-256: 88afda8977285f3839ebad1cb785a344b296c6890f3f901746259dd478ab9fb7
shasum -a 256 "$G/root.elf"
for c in cell impostor; do
  text=$([ $c = cell ] && echo "itonami cell: hello from a loaderless static image" || echo "itonami cell: an impostor")
  printf '(ns itonami-demo-%s (:export [main]))\n(defn main [] :i64 (let [x (typed-cap-call :io/write :string :string "%s\\n")] 0))\n' "$c" "$text" > "$G/$c.kotoba"
done
pin() { git -C "$K/$1" show "$2:$3" | sed '1,/\]))$/d'; }
{
  echo '(ns itonami-cell-run (:export [main]))'
  pin org-nist-sha2 ea83e70a6a8efb8fb12dd5f0031fcda35a9ec77b kotoba/sha2/core.kotoba
  pin org-ietf-ed25519 6f17477b5be91a0c8c369b8d30e7e5ea0c10a8aa kotoba/ed25519/selfhost.kotoba
  pin org-ietf-jcs 6331a9b461f5858be484b9d6859f314f9c3a2510 kotoba/jcs/reader.kotoba
  pin org-oci-distribution 90463924db65eea4d9021dc6623105472282b5a9 kotoba/oci/itonami_artifact.kotoba
  pin grant fa7e9b567286cd38cc4b229d8c980bfe676d9cce kotoba/grant/runtime_class.kotoba
  pin tsukasa 03e2322489839be2176d5c23d0ccb4120d1b0ee8 kotoba/tsukasa/ledger_core.kotoba
  pin tsukasa 03e2322489839be2176d5c23d0ccb4120d1b0ee8 kotoba/tsukasa/node_state.kotoba
  sed '1,/\]))$/d' kotoba/fleet_node_enrollment.kotoba
  sed '1,/\]))$/d' kotoba/fleet_cell_launch.kotoba
  sed '1,/\]))$/d' kotoba/fleet_oci_layout.kotoba
  sed '1,/\]))$/d' kotoba/fleet_cell_run.kotoba
  echo '(defn main [] :i64 (cell-run-main))'
} > "$G/agent.kotoba"
# controls: an agent that starts the container whatever the verdict says, and
# one that does not compare the layout's index.json with the release
sed 's/(if (not (= verdict "launch\/ok")) (string-concat "refused " verdict)/(if false (string-concat "refused " verdict)/' "$G/agent.kotoba" > "$G/unchecked.kotoba"
sed 's/(not (ol-index-ok? xb x0 manifest-digest manifest-size)) "layout\/index-mismatch"/false "layout\/index-mismatch"/' \
  "$G/agent.kotoba" > "$G/no-index-check.kotoba"
podman run --rm --pull never --platform linux/arm64 --network none \
  -e KEXE_CAP_RESOURCES_35=/gate -v "$G:/gate" -w /gate docker.io/library/python:3.12-alpine sh -c '
    for c in cell impostor agent unchecked no-index-check; do
      rm -f /gate/$c.elf
      ./root.elf compile /gate/$c.kotoba --target aarch64-linux --output /gate/$c.elf --format static >/dev/null || exit 1
      chmod +x /gate/$c.elf
    done'
for c in cell impostor; do
  printf 'FROM scratch\nCOPY %s.elf /cell\nENTRYPOINT ["/cell"]\n' $c > "$G/Containerfile.$c"
  podman build -q --platform linux/arm64 -t localhost/itonami-cell-demo:$c -f "$G/Containerfile.$c" "$G"
  rm -rf "$G/layout-$c"; podman save -q --format oci-dir -o "$G/layout-$c" localhost/itonami-cell-demo:$c
done
# RFC 8032 test key 1, for this gate only
printf '9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60\n' > "$G/node.key"
python3 -I - "$G" <<'PY'
import hashlib, json, os, shutil, sys
g = sys.argv[1]
idx = json.load(open(f"{g}/layout-cell/index.json"))["manifests"][0]
md, ms = idx["digest"], idx["size"]
m = '{"mediaType":"application/vnd.oci.image.manifest.v1+json","digest":"' + md + '","size":' + str(ms) + '}'
for name, cls in [("release", "container"), ("other", "wasm")]:
    b = ('{"schemaVersion":2,"mediaType":"application/vnd.oci.image.index.v1+json","artifactType":"application/vnd.itonami.app.v1",'
         f'"manifests":[{m}],"annotations":{{"org.itonami.runtime-class":"{cls}"}}}}').encode()
    open(f"{g}/{name}.json", "wb").write(b)
    open(f"{g}/{name}.digest", "w").write("sha256:" + hashlib.sha256(b).hexdigest())
man = json.load(open(f"{g}/layout-cell/blobs/sha256/{md[7:]}"))
layer = man["layers"][0]["digest"][7:]
for name in ["tampered", "missing"]:
    shutil.rmtree(f"{g}/layout-{name}", ignore_errors=True); shutil.copytree(f"{g}/layout-cell", f"{g}/layout-{name}")
p = f"{g}/layout-tampered/blobs/sha256/{layer}"; b = bytearray(open(p, "rb").read()); b[len(b) // 2] ^= 1; open(p, "wb").write(bytes(b))
os.remove(f"{g}/layout-missing/blobs/sha256/{layer}")
# the impostor's layout, with every blob of the released image copied in
shutil.rmtree(f"{g}/layout-swapped", ignore_errors=True); shutil.copytree(f"{g}/layout-impostor", f"{g}/layout-swapped")
for f in os.listdir(f"{g}/layout-cell/blobs/sha256"):
    shutil.copy(f"{g}/layout-cell/blobs/sha256/{f}", f"{g}/layout-swapped/blobs/sha256/{f}")
PY
R=$(cat "$G/release.digest"); O=$(cat "$G/other.digest")
A=9af9509efcef6c13f34293a15319f37468ca5f42a55383a0ac8c270a8e678c29   # L1: b70's admitted declaration
N=1792500000000
run() {  # name agent layout index-file l1-digest admitted allow
  podman machine ssh -- "cd $G && env KEXE_ALLOW=20,35,37,38 KEXE_CAP_RESOURCES_35=$G KEXE_CAP_RESOURCES_20=/usr/bin/podman \
    KEXE_SPAWN_ENV=HOME:XDG_RUNTIME_DIR:PATH ./$2.elf $G/$4 $5 container $G/$3 $G/node.key b70 site-a $6 $N $7" \
    > "$G/$1.out" 2>&1 || true   # a refused cell exits 1
  echo "$1: $(grep -E '^(verdict|layout) ' "$G/$1.out" | tr '\n' ' ')$(grep -q '^exit ' "$G/$1.out" && echo '| container ran')"
}
run honest agent layout-cell release.json "$R" "$A" 2
run digest-mismatch agent layout-cell release.json "$O" "$A" 2
run not-granted agent layout-cell release.json "$R" "$A" 1
run not-admitted agent layout-cell release.json "$R" 0000000000000000000000000000000000000000000000000000000000000000 2
run tampered-layer agent layout-tampered release.json "$R" "$A" 2
run missing-blob agent layout-missing release.json "$R" "$A" 2
run swapped-index agent layout-swapped release.json "$R" "$A" 2
run control-verdict unchecked layout-cell release.json "$O" "$A" 2
run control-index no-index-check layout-swapped release.json "$R" "$A" 2
grep -h '^receipt ' "$G/control-index.out" | head -1 | cut -c1-24; podman machine ssh -- "podman run --rm --network none --read-only oci:$G/layout-swapped 2>/dev/null"
```

Expected output, measured on 2026-10-23 (the podman machine, arm64 Linux
6.15, rootless podman):

```text
honest: verdict launch/ok layout layout/ok | container ran
digest-mismatch: verdict launch/digest-mismatch
not-granted: verdict grant/runtime-class-not-granted
not-admitted: verdict launch/node-not-active
tampered-layer: verdict launch/ok layout layout/digest-mismatch
missing-blob: verdict launch/ok layout layout/missing-blob
swapped-index: verdict launch/ok layout layout/index-mismatch
control-verdict: verdict launch/digest-mismatch layout layout/ok | container ran
control-index: verdict launch/ok layout layout/ok | container ran
receipt {"at":1792500000
itonami cell: an impostor
```

- **not-admitted.** L1 admits another declaration, so the node's own state
  is `admitted` (held) and not `active`.
- **tampered-layer and missing-blob.** One byte of the layer is changed, or
  the layer is removed. The agent refuses before podman runs.
- **swapped-index.** The directory holds every blob of the released image,
  so the named manifest, config and layer all verify. Its `index.json`,
  however, points to the impostor, and podman runs what `index.json` names.
  The index check refuses it.
- **control-index.** Without that check the agent admits the swapped
  directory, and podman runs the impostor (the last line), under a receipt
  for the release. **control-verdict** shows the same for the release
  verdict.

**Check the honest receipt independently.** OpenSSL verifies the Ed25519
signature with the did's key, and `hashlib` recomputes both CIDs and the
canonical text:

```sh
python3 -I - "$G" <<'PY'
import base64, hashlib, json, subprocess, sys, os
g = sys.argv[1]
lines = dict(l.split(" ", 1) for l in open(f"{g}/honest.out").read().splitlines() if " " in l and not l.startswith("{"))
text, sig = lines["receipt"], lines["signature"]
r = json.loads(text)
B58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
n = 0
for c in r["did"][9:]: n = n * 58 + B58.index(c)
pk = n.to_bytes(34, "big")[2:]
der = bytes.fromhex("302a300506032b6570032100") + pk
open(f"{g}/pub.pem", "w").write("-----BEGIN PUBLIC KEY-----\n" + base64.b64encode(der).decode() + "\n-----END PUBLIC KEY-----\n")
open(f"{g}/receipt.txt", "wb").write(text.encode())
open(f"{g}/receipt.sig", "wb").write(base64.urlsafe_b64decode(sig + "=="))
ossl = next(p for p in ["/opt/homebrew/opt/openssl@3/bin/openssl", "/usr/bin/openssl"] if os.path.exists(p))
v = subprocess.run([ossl, "pkeyutl", "-verify", "-pubin", "-inkey", f"{g}/pub.pem", "-rawin",
                    "-in", f"{g}/receipt.txt", "-sigfile", f"{g}/receipt.sig"], capture_output=True, text=True)
def cid(d): return "b" + base64.b32encode(bytes([1, 0x55, 0x12, 0x20]) + d).decode().lower().rstrip("=")
out = b"itonami cell: hello from a loaderless static image\n"
print("signature:", v.stdout.strip() or v.stderr.strip())
print("canonical:", json.dumps(r, sort_keys=True, separators=(",", ":")) == text)
print("cell CID:", r["cell"] == cid(bytes.fromhex(open(f"{g}/release.digest").read()[7:])))
print("output CID:", r["output"] == cid(hashlib.sha256(out).digest()), "exit", r["exit"])
PY
```

Expected: `Signature Verified Successfully`, then `canonical: True`,
`cell CID: True` and `output CID: True exit 0`. The receipt is
[evidence/cell-run-layout-20261023.json](evidence/cell-run-layout-20261023.json). The first version, which
ran the image by repository digest, is recorded in
[evidence/cell-run-20261023.json](evidence/cell-run-20261023.json).

The images `localhost/itonami-cell-demo:cell`, `:impostor` and `:slow`
stay in the local podman store until they are removed (`podman rmi
localhost/itonami-cell-demo:cell localhost/itonami-cell-demo:impostor
localhost/itonami-cell-demo:slow`).

## Process table

The acceptance also asks that the start path has no Node.js, nbb, JVM,
babashka or Cloudflare, checked in the process table. The same `$G` and
agent are used with a cell that computes for about 3 seconds, so its
processes can be seen. `ps` is sampled every 0.2 s until a process named
`cell` exists:

```sh
printf '(ns itonami-demo-slow (:export [main]))\n(defn- spin [n :i64] :i64 (loop [i 0 a 0] (if (= i n) a (recur (+ i 1) (+ a (rem i 7))))))\n(defn main [] :i64 (let [s (spin 300000000) x (typed-cap-call :io/write :string :string (string-concat "itonami cell: slow, " (string-concat (string-from-i64 (rem s 10)) "\\n")))] 0))\n' > "$G/slow.kotoba"
podman run --rm --pull never --platform linux/arm64 --network none -e KEXE_CAP_RESOURCES_35=/gate -v "$G:/gate" -w /gate \
  docker.io/library/python:3.12-alpine sh -c 'rm -f slow.elf; ./root.elf compile /gate/slow.kotoba --target aarch64-linux --output /gate/slow.elf --format static >/dev/null && chmod +x slow.elf'
printf 'FROM scratch\nCOPY slow.elf /cell\nENTRYPOINT ["/cell"]\n' > "$G/Containerfile.slow"
podman build -q --platform linux/arm64 -t localhost/itonami-cell-demo:slow -f "$G/Containerfile.slow" "$G"
rm -rf "$G/layout-slow"; podman save -q --format oci-dir -o "$G/layout-slow" localhost/itonami-cell-demo:slow
python3 -I - "$G" <<'PY'
import hashlib, json, sys
g = sys.argv[1]
idx = json.load(open(f"{g}/layout-slow/index.json"))["manifests"][0]
m = '{"mediaType":"application/vnd.oci.image.manifest.v1+json","digest":"' + idx["digest"] + '","size":' + str(idx["size"]) + '}'
b = ('{"schemaVersion":2,"mediaType":"application/vnd.oci.image.index.v1+json","artifactType":"application/vnd.itonami.app.v1",'
     f'"manifests":[{m}],"annotations":{{"org.itonami.runtime-class":"container"}}}}').encode()
open(f"{g}/release-slow.json", "wb").write(b); open(f"{g}/release-slow.digest", "w").write("sha256:" + hashlib.sha256(b).hexdigest())
PY
cat > "$G/ps-snap.sh" <<EOF
cd $G
env KEXE_ALLOW=20,35,37,38 KEXE_CAP_RESOURCES_35=$G KEXE_CAP_RESOURCES_20=/usr/bin/podman KEXE_SPAWN_ENV=HOME:XDG_RUNTIME_DIR:PATH \
  ./agent.elf $G/release-slow.json $(cat "$G/release-slow.digest") container $G/layout-slow $G/node.key b70 site-a $A $N 2 > $G/slow-run.out 2>&1 &
AGENT=\$!
for i in \$(seq 1 100); do
  ps -eo pid,ppid,user,comm,args > /tmp/ps-snap.txt
  if awk '\$4 == "cell"' /tmp/ps-snap.txt | grep -q .; then cp /tmp/ps-snap.txt $G/slow-ps.txt; break; fi
  sleep 0.2
done
wait \$AGENT
EOF
podman machine ssh -- "sh $G/ps-snap.sh"
python3 -I - "$G/slow-ps.txt" <<'PY'
import sys
procs = {}
for l in list(open(sys.argv[1]))[1:]:
    p = l.split(None, 4); procs[int(p[0])] = (int(p[1]), p[3], p[4].strip() if len(p) > 4 else "")
def show(pid, depth):
    print("  " * depth + procs[pid][1] + ": " + procs[pid][2][:60])
    for c in sorted(c for c, v in procs.items() if v[0] == pid): show(c, depth + 1)
show(min(pid for pid, v in procs.items() if v[1] == "agent.elf"), 0)
for c in [pid for pid, v in procs.items() if v[1] == "cell"]:
    chain, p = [], c
    while p in procs and p != 1: chain.append(procs[p][1]); p = procs[p][0]
    print("cell ancestry:", " <- ".join(chain))
print("excluded runtimes present:", sorted({v[1] for v in procs.values() if v[1] in ("node", "nbb", "java", "bb", "babashka", "clojure")}))
PY
```

Measured on 2026-10-23 (local paths shortened to `G`):

```text
agent.elf: ./agent.elf G/release-slow.json sha256:7dc1f7ee66725
  agent.elf: ./agent.elf G/release-slow.json sha256:7dc1f7ee667
    podman: /usr/bin/podman run --rm --pull never --network none --rea…
cell ancestry: cell <- conmon
excluded runtimes present: []
```

The process tree has three levels:
- the agent;
- its spawn helper, which is forked before the sandbox and is the only
  process that may execute anything;
- the granted podman, with its fixed argv.

The container runs under `conmon`, which podman detaches to the user's
systemd. No `node`, `nbb`, `java`, `bb`, `babashka` or `clojure` process runs
anywhere on the VM at that moment. The agent's own output for that run was
`verdict launch/ok`, `layout layout/ok`, `exit 0` and `self-check enroll/ok
1`. The receipt is
[evidence/cell-run-process-table-20261023.json](evidence/cell-run-process-table-20261023.json).
It records only the agent's subtree and the cell's ancestry.

## What this does not show

- Pulling the release from a registry. The layout is a local directory.
- Another process writing to the layout directory between the check and the
  run. The directory must stay under the agent's own scope.
- A second node.
- Persistence of the receipt (kotobase, yataverse).
- The persistent container stdio channel (JUN-298).
- A first rootless podman start after boot under `no_new_privs`. Here the
  user namespace already existed.
- x86_64 execution.
