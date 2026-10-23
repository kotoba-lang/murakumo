"""The conflicting topology for docs/topology-replica.md: revision 7 like
topology.json (Membership), but with k16 enabled, signed by RFC 8032 key 1
(the operator) with OpenSSL 3. A replica that holds revision 7 must record it
as a conflict, not adopt it."""
import base64, json, os, subprocess, sys
OSSL = next(p for p in ["/opt/homebrew/opt/openssl@3/bin/openssl", "/usr/bin/openssl"] if os.path.exists(p))
out = sys.argv[1]
seed = bytes.fromhex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
der = bytes.fromhex("302e020100300506032b657004220420") + seed
open(f"{out}/op.pem", "w").write("-----BEGIN PRIVATE KEY-----\n" + base64.b64encode(der).decode() + "\n-----END PRIVATE KEY-----\n")
node = lambda n, en: {"node": n, "url": "http://100.64.0.10:8796/", "enabled": en, "owner-id": "01234567-89ab-cdef-0123-456789abcdef", "roles": []}
p = {"schema": 1, "revision": 7, "nodes": [node("b70", True), node("k16", True)],
     "gateways": [{"node": "gw", "url": "http://100.64.0.1:8796/", "enabled": True}], "policies": {"minimum-replicas": {"m1": 1}}}
open(f"{out}/m.bin", "wb").write(json.dumps(p, sort_keys=True, separators=(",", ":")).encode())
sig = base64.urlsafe_b64encode(subprocess.run([OSSL, "pkeyutl", "-sign", "-inkey", f"{out}/op.pem", "-rawin", "-in", f"{out}/m.bin"],
                                              capture_output=True, check=True).stdout).rstrip(b"=").decode()
open(f"{out}/topology-r7-conflict.json", "w").write(json.dumps({"payload": p, "signature": sig}))
os.remove(f"{out}/op.pem"); os.remove(f"{out}/m.bin")
