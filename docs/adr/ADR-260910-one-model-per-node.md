# ADR-260910: a node serves one model

Status: Accepted — 2026-09-10. Owner instruction: 「murakumo cloud fleet で,
基本的に1台当たり1つの model しか host しないように設計してください」.

## The rule

**A node serves at most one model.**

The limit is on the NODE side only. A model may be served by many nodes, and
`murakumo.infer.plan` cutting one model across ranks stays legal — that is the
dual of this rule, not a violation of it.

**Serving is not caching.** ADR-260712 already decided that "model cache
placement does not imply runtime compatibility"; a node may hold any number of
model files on disk. This binds the ENGINE, not the filesystem.

「基本的に」 is honoured by a DECLARED exception, not by an implicit one:
`:node/serves-exception` requires `:reason`, `:adr` and `:until`, all three, and
an expired exception is a violation. An exception nobody has to write down is
indistinguishable from the rule not existing.

## Two planes, and neither substitutes for the other

    declared    fleet.edn `:node/serves`      what a node is SUPPOSED to serve
    observed    murakumo.fleet.one-model      what a node IS serving, from ps

`murakumo.fleet.one-model` (separate change, same day) classifies a node's
process list and measured the rule broken on every reachable mini — llama-server
+ rpc-server + ComfyUI + ollama, four model planes on one 16 GiB machine. This
ADR is the other half: a declaration nothing enforces is a wish, and a
measurement nothing declares has nothing to be measured against. Reconciling
the two is a third thing and is not done here.

## What the declaration found on its first run

**`:model/replicas` places from the MODEL side and contradicts this rule.** It
survives on two models in `infer.edn`, has ZERO readers anywhere in the tree,
and one of the two says `:all-edge-nodes` — so the only machine-readable
placement statement in the system asserted that every edge node hosts that
model, which is exactly what this rule forbids. It is REPORTED rather than
deleted: a field nothing reads is not load-bearing, but silently removing a
declaration of intent loses the intent. The node side is authoritative; that
field must go deliberately, not as a side effect of this change.

**The registered endpoint id encodes the node.** `fleet.edn`'s own prose says
xavier is registered as model `qwen3-vl-30b-a3b-xavier-cuda`, and the catalogue
defines `qwen3-vl-30b-a3b`. The `-xavier-cuda` suffix exists because the
registry could not assume one model per node and had to disambiguate. Under
this rule `node -> model` is a function, so the node is the key and the model is
the value; encoding the node in the model id says the same thing twice. The
declaration uses the catalogue id. Renaming the registered endpoint is a
separate act with its own cutover and is NOT done here.

## Enforcement

`scripts/verify-one-model-per-node.cljk`. Four exit codes, four different
answers, deliberately not collapsed:

    0  every node stated, none violating
    1  a violation — two or more models with no live exception, an expired
       exception, or a model id nothing defines
    2  could not answer — a file would not read, or zero nodes were scanned
    3  no violations, but N nodes are :unstated

**`:unstated` is not conformant.** An undeclared node is unmeasured, and
reporting it as a pass is the failure this workspace's rules name first. Today
11 of 12 nodes are `:unstated`, so the check exits 3 — and that is the honest
state, not a bug in the check. Closing them means reading what each node
actually runs, which is what the observed plane does.

`:none` is a real value and is conformant: a node that deliberately serves
nothing is available, and `murakumo.infer.rebalance` already reserves exactly
one head/relay that never holds a shard.

Discrimination measured 2026-09-10, four states, each by its own reason:

    as committed                        exit 3   11 unstated, 0 violations
    two models, no exception            exit 1   "serves 2 models with no ..."
    two models, exception until 2026-01 exit 1   "exception expired on 2026-01-01"
    two models, exception until 2027-01 exit 3   ok, "exception until 2027-01-01"

## Not done here

The check is `.cljs` (nbb), matching this repo's seven existing scripts.
CLAUDE.md's kbb-first rule applies to it; it should migrate with the other
seven rather than become the lone exception. Named so the deviation is a
decision rather than an oversight.

Also not done: retiring `:model/replicas`, renaming the registered endpoint,
declaring the 11 unstated nodes, and a fleet gate. Each is its own change.
