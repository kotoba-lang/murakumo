"""Vectors for kotoba/fleet_topology_verify.kotoba (docs/topology-verify.md).
Run in an empty directory: OpenSSL 3 signs, json writes the documents."""
import base64, json, subprocess, os
OSSL = next(p for p in ["/opt/homebrew/opt/openssl@3/bin/openssl", "/usr/bin/openssl"] if os.path.exists(p))
B58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
def b58(b):
    n = int.from_bytes(b, "big"); out = ""
    while n: n, r = divmod(n, 58); out = B58[r] + out
    return out
def pem(seed):
    der = bytes.fromhex("302e020100300506032b657004220420") + seed
    open("k.pem", "w").write("-----BEGIN PRIVATE KEY-----\n" + base64.b64encode(der).decode() + "\n-----END PRIVATE KEY-----\n")
def did(seed):
    pem(seed); der = subprocess.run([OSSL, "pkey", "-in", "k.pem", "-pubout", "-outform", "DER"], capture_output=True, check=True).stdout
    return "did:key:z" + b58(b"\xed\x01" + der[-32:])
def sign(seed, msg):
    pem(seed); open("m.bin", "wb").write(msg)
    return base64.urlsafe_b64encode(subprocess.run([OSSL, "pkeyutl", "-sign", "-inkey", "k.pem", "-rawin", "-in", "m.bin"], capture_output=True, check=True).stdout).rstrip(b"=").decode()
seeds = {"op1": "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60",
         "op2": "4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb",
         "op3": "c5aa8df43f9f837bedb7442f31dcb7b166d38535076f094b85ce3a2e0b4458f7"}
seeds = {k: bytes.fromhex(v) for k, v in seeds.items()}
dids = {k: did(v) for k, v in seeds.items()}
def payload(rev=7, schema=1, extra=None):
    p = {"schema": schema, "revision": rev,
         "nodes": [{"node": "b70", "url": "http://100.64.0.10:8796/", "enabled": True,
                    "owner-id": "01234567-89ab-cdef-0123-456789abcdef", "roles": [{"kind": "text", "model": "m1"}]}],
         "gateways": [{"node": "gw", "url": "http://100.64.0.1:8796/", "enabled": True}],
         "policies": {"minimum-replicas": {"m1": 1}}}
    if extra: p.update(extra)
    return p
canon = lambda p: json.dumps(p, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()
P7 = payload(); P8 = payload(rev=8); PS = payload(schema=2); PF = payload(extra={"weight": 0.5})
s = {k: sign(v, canon(P7)) for k, v in seeds.items()}
docs = {
  "single": json.dumps({"payload": P7, "signature": s["op1"]}),
  "quorum": json.dumps({"payload": P7, "signatures": [{"key": "op2", "signature": s["op2"]}, {"key": "op1", "signature": s["op1"]}]}),
  "one-of-two": json.dumps({"payload": P7, "signatures": [{"key": "op1", "signature": s["op1"]}]}),
  "tampered": json.dumps({"payload": P8, "signature": s["op1"]}),
  "mislabeled": json.dumps({"payload": P7, "signatures": [{"key": "op1", "signature": s["op3"]}]}),
  "duplicate-payload": '{"payload": ' + json.dumps(P8) + ', "payload": ' + json.dumps(P7) + ', "signature": "' + s["op1"] + '"}',
  "float": json.dumps({"payload": PF, "signature": sign(seeds["op1"], canon(PF))}),
  "same-key-twice": json.dumps({"payload": P7, "signature": s["op1"], "signatures": [{"key": "op1", "signature": s["op1"]}]}),
  "schema-2": json.dumps({"payload": PS, "signature": sign(seeds["op1"], canon(PS))}),
}
json.dump({"dids": dids, "docs": docs, "canonical": canon(P7).decode()}, open("vectors.json", "w"), indent=1)
os.remove("k.pem"); os.remove("m.bin")
for k, v in dids.items(): print(k, v)
print("canonical", canon(P7).decode()[:80])
print("single", docs["single"][:80])
