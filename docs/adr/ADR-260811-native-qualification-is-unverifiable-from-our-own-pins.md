# ADR-260811 — Native qualification is not verifiable from our own pins

- Status: accepted — the migration it named is done (2026-08-11, same day)
- Date: 2026-08-11

## The claim that could not be checked here

compiler (now `kotoba-lang/amu`) ADR-0221 records **33/33** of this
repository's `kotoba/*_core.kotoba` compiling for both native ISAs. That number
is about murakumo, and it has never been reproducible *from* murakumo.

It stopped being true on 2026-08-06, when kotoba-native `d7de271` added a
record-boundary check that read the whole function body instead of tail
position. Six modules — `infer_credits`, `infer_plan`, `infer_rebalance`,
`infer_schedule`, `reconcile_plan`, `task_plan` — stopped qualifying, every one
on `:result-schema-mismatch` and nothing else. Nobody noticed for five days.
kotoba-native ADR-0032 fixed it and re-measured 33/33 on both ISAs.

**Our suite stayed green through all of it**, and would stay green through the
next one. The 35 `*_kotoba_parity_test` namespaces run the cores through the
KIR interpreter on the **wasm32** target. Semantics were never in question;
ADMISSION was, and nothing here asks the native backends anything.

## Why simply adding the test does not work

`test/murakumo/kotoba_native_qualification_test.clj` (on
`agent/native-qualification-sweep`, unmerged) enumerates `kotoba/*.kotoba` from
disk and compiles each for `:x86_64-kotoba-v1` and `:aarch64-kotoba-v1`.
Against our pins it reports **0 of 35**, on both ISAs:

```
typed values currently require the kotoba-script web target, typed Wasm/CLJS
target, or the qualified native one-word string/record/variant/option/result slice
```

Not a defect in the cores. Our `:test` alias pins the compiler at `98b56bd`
(Profile 5), **339 commits** behind amu — before ADR-0219 let a record cross
the parameter boundary, which is the change that took these modules from 0/33
to 30/33 in the first place. We have been measuring parity against a compiler
that predates native records existing.

## What advancing it costs, measured

Advancing the test-only compiler to amu `e78241d` needs the production
`kotoba-kir` pin advanced with it, `767f2f2` → `d58972d`: the old Profile 5 pin
predates `kotoba.kir.descriptor`, so amu's `kotoba.wasm.typed` cannot load
against it at all. They are one unit.

With that pair, the qualification sweep passes **35/35 on both ISAs** — the
first time this has been checkable here — and `clojure -M:test:gen` rewrites all
35 shipped artifacts **byte-identically**. KIR emission did not move across 339
commits of compiler.

### The first attempt blamed the wrong thing

A first pass also advanced the `abi` override to amu's own pin (`32ee84b`), on
the strength of a comment in `deps.edn` saying it "must match compiler's abi
pin". That pass regressed eight tests in `component_authority` and
`overlay_witness`, all through:

```
ExceptionInfo: Complete Component authority signing input is required
  #:murakumo.component{:reason :invalid-signing-input}
  at murakumo.component_authority/sign-event (component_authority.cljc:111)
```

This ADR originally recorded that as the shipped KIR being generated for one
kir and executed by another. **That explanation was wrong** — the artifacts are
byte-identical, so they cannot be the difference. Isolating it:

| pins | `component_authority` |
|---|---|
| kir + amu + abi advanced | 2 failures, 2 errors |
| kir + amu advanced, abi held | **0 failures, 0 errors** |

It is the `abi` advance alone, through
`abi/valid-component-authority-event?`. The comment was taken on trust rather
than tested; held at `34415ce`, everything passes. Advancing `abi` is a
separate migration nobody has done, and `deps.edn` now says so where the pin is.

### Result

Full suite, same host, against current `main`:

| | tests | failures | errors |
|---|---|---|---|
| `origin/main` | 642 | 4 | 0 |
| migrated | 644 | 4 | 0 |

The failing sets are **identical** — `aiueos-object-source-is-present` and
`ledger-quorum-fn-reaches-witnessed-over-real-quic`, both already failing on
`main` and neither touched here.

## Position

Native qualification **is** now checkable from these pins, and
`kotoba_native_qualification_test` checks it on every run: every
`kotoba/*.kotoba`, both ISAs, module list read from disk so a new core is
covered by existing rather than by remembering to edit a list.

Two things stay true and are worth keeping in view:

- The sweep asks for *admission*, not execution. It proves the backends accept
  these modules, not that a native process ran them.
- `abi` remains pinned behind amu's. That is a known, measured, deliberate hold
  — not an oversight — and closing it is its own change with its own evidence.

## Also recorded: adding a core is four things, not one

Twice in one day a core landed as a source file alone and left `origin/main`
red. A core is:

1. `kotoba/<name>_core.kotoba`
2. its entry in `murakumo.kotoba.oracle`'s catalog
3. its shipped `resources/murakumo/oracle/<name>_core.kir.edn`
   (`clojure -M:test:gen`)
4. the catalog counts in `kotoba_oracle_cljs_load_test`

`prices_core` (a021cbe) shipped only (1) and needed cf6012b; `windows_core`
(2ee2575) shipped only (1) and needed #285. Both were merged after their own
namespace's tests passed and without the full suite — which is exactly the gap:
a per-namespace run cannot see a repository-level invariant. The counts are now
`(<= 35 …)` and a `preload == catalog-count` relation, so (4) no longer needs
an edit per core; (2) and (3) still do.
