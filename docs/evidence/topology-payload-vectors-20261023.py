"""Payload cases for kotoba/fleet_topology_verify.kotoba topology-payload-problem,
the Kotoba port of src/murakumo/infer/replicated_topology.cljk validate!
(allow-loopback false). Each case changes one thing in a valid payload; the
expected refusal is read from validate!'s order of checks (no JavaScript
runs). `emit` prints the Kotoba vectors and test function."""
import copy, json, sys

BASE = {"schema": 1, "revision": 7,
        "nodes": [{"node": "b70", "url": "http://100.64.0.10:8796/", "enabled": True,
                   "owner-id": "01234567-89ab-cdef-0123-456789abcdef", "roles": [{"kind": "text", "model": "m1"}]}],
        "gateways": [{"node": "gw", "url": "http://100.64.0.1:8796/", "enabled": True}],
        "policies": {"minimum-replicas": {"m1": 1}}}
N0 = ("nodes", 0)
def node(**kw): return lambda p: p["nodes"][0].update(kw)
def gw(**kw): return lambda p: p["gateways"][0].update(kw)
def setp(k, v): return lambda p: p.__setitem__(k, v)
def delp(k): return lambda p: p.pop(k)
def reps(v): return lambda p: p["policies"].__setitem__("minimum-replicas", v)
def second(name): return lambda p: p["nodes"].append(dict(copy.deepcopy(p["nodes"][0]), node=name))
def gw2(name): return lambda p: p["gateways"].append(dict(copy.deepcopy(p["gateways"][0]), node=name))
E = "\U0001F600"  # two UTF-16 code units
CASES = [
    ("ok", lambda p: None, "topology/ok"),
    ("ok-no-slash", node(url="http://100.64.0.10:8796"), "topology/ok"),
    ("ok-8797", node(url="http://100.64.0.10:8797/"), "topology/ok"),
    ("ok-disabled", node(enabled=False), "topology/ok"),
    ("ok-extra-field", node(extra=1), "topology/ok"),
    ("ok-no-roles", node(roles=[]), "topology/ok"),
    ("ok-model-128", node(roles=[{"kind": "image", "model": "a" * 128}]), "topology/ok"),
    ("ok-model-64-pairs", node(roles=[{"kind": "text", "model": E * 64}]), "topology/ok"),
    ("ok-two-nodes", second("k16"), "topology/ok"),
    ("ok-no-replicas", reps({}), "topology/ok"),
    ("ok-two-replicas", reps({"m1": 1, "m2": 256}), "topology/ok"),
    ("revision-zero", setp("revision", 0), "topology/invalid-structure"),
    ("nodes-empty", setp("nodes", []), "topology/invalid-structure"),
    ("nodes-missing", delp("nodes"), "topology/invalid-structure"),
    ("nodes-object", setp("nodes", {}), "topology/invalid-structure"),
    ("gateways-17", setp("gateways", [{"node": f"g{i}", "url": "http://100.64.0.1:8796/", "enabled": True} for i in range(17)]), "topology/invalid-structure"),
    ("nodes-duplicate", second("b70"), "topology/duplicate-identity"),
    ("gateways-duplicate", gw2("gw"), "topology/duplicate-identity"),
    ("name-underscore", node(node="b_70"), "topology/invalid-endpoint"),
    ("name-empty", node(node=""), "topology/invalid-endpoint"),
    ("name-number", node(node=70), "topology/invalid-endpoint"),
    ("url-https", node(url="https://100.64.0.10:8796/"), "topology/invalid-endpoint"),
    ("url-port-8000", node(url="http://100.64.0.10:8000/"), "topology/invalid-endpoint"),
    ("url-no-port", node(url="http://100.64.0.10/"), "topology/invalid-endpoint"),
    ("url-private-10", node(url="http://10.0.0.1:8796/"), "topology/invalid-endpoint"),
    ("url-second-128", node(url="http://100.128.0.1:8796/"), "topology/invalid-endpoint"),
    ("url-octet-256", node(url="http://100.64.0.256:8796/"), "topology/invalid-endpoint"),
    ("url-path", node(url="http://100.64.0.10:8796/x"), "topology/invalid-endpoint"),
    ("url-query", node(url="http://100.64.0.10:8796/?a"), "topology/invalid-endpoint"),
    ("url-userinfo", node(url="http://u@100.64.0.10:8796/"), "topology/invalid-endpoint"),
    ("url-loopback", node(url="http://127.0.0.1:8796/"), "topology/invalid-endpoint"),
    ("enabled-string", node(enabled="yes"), "topology/invalid-endpoint"),
    ("gateway-url", gw(url="http://100.64.0.1:9000/"), "topology/invalid-endpoint"),
    ("owner-short", node(**{"owner-id": "0123"}), "topology/invalid-roles"),
    ("owner-upper", node(**{"owner-id": "01234567-89AB-CDEF-0123-456789ABCDEF"}), "topology/invalid-roles"),
    ("owner-missing", lambda p: p["nodes"][0].pop("owner-id"), "topology/invalid-roles"),
    ("roles-9", node(roles=[{"kind": "text", "model": "m1"}] * 9), "topology/invalid-roles"),
    ("role-audio", node(roles=[{"kind": "audio", "model": "m1"}]), "topology/invalid-roles"),
    ("role-not-object", node(roles=["text"]), "topology/invalid-roles"),
    ("model-empty", node(roles=[{"kind": "text", "model": ""}]), "topology/invalid-roles"),
    ("model-129", node(roles=[{"kind": "text", "model": "a" * 129}]), "topology/invalid-roles"),
    ("model-65-pairs", node(roles=[{"kind": "text", "model": E * 65}]), "topology/invalid-roles"),
    ("policies-missing", delp("policies"), "topology/invalid-policy"),
    ("replicas-zero", reps({"m1": 0}), "topology/invalid-policy"),
    ("replicas-257", reps({"m1": 257}), "topology/invalid-policy"),
    ("replicas-string", reps({"m1": "1"}), "topology/invalid-policy"),
    ("replicas-empty-key", reps({"": 1}), "topology/invalid-policy"),
    ("replicas-second-bad", reps({"m1": 1, "m2": 0}), "topology/invalid-policy"),
    ("replicas-array", reps([1]), "topology/invalid-policy"),
]
# stricter than endpoint? through WHATWG URL parsing, which accepts these
STRICTER = [
    ("strict-upper-scheme", node(url="HTTP://100.64.0.10:8796/"), "topology/invalid-endpoint"),
    ("strict-octal-octet", node(url="http://100.64.0.010:8796/"), "topology/invalid-endpoint"),
]

def doc(mut):
    p = copy.deepcopy(BASE); mut(p)
    return json.dumps({"payload": p}, separators=(",", ":"), ensure_ascii=True)

def kotoba_string(s): return '"' + s.replace("\\", "\\\\").replace('"', '\\"') + '"'

def emit():
    out = [";; topology-payload-problem cases (docs/evidence/topology-payload-vectors-20261023.py emit)"]
    for name, mut, _ in CASES + STRICTER:
        out.append(f"(defn- tv-p-{name} [] :vector-i64 (tv-bytes {kotoba_string(doc(mut))}))")
    out.append("")
    out.append("(defn test-payload [] :bool")
    lines = [f'(= (topology-payload-problem (tv-p-{n})) "{e}")' for n, _, e in CASES + STRICTER]
    out.append("  (and " + "\n       ".join(lines) + "))")
    print("\n".join(out))

if __name__ == "__main__":
    {"emit": emit}[sys.argv[1]]()
