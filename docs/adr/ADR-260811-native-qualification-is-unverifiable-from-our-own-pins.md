# ADR-260811 — Native qualification is not verifiable from our own pins

- Status: accepted (statement of position; the migration it names is not done)
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

Advancing the test-only compiler to amu `e78241d` needs `kotoba-kir` overridden
to amu's `d58972d` as well: our production kir pin (`767f2f2`, Profile 5)
predates `kotoba.kir.descriptor`, so amu's `kotoba.wasm.typed` cannot load at
all against it. With both overridden, the qualification sweep passes **35/35 on
both ISAs** — the first time this has been checkable here.

The full suite does not. Measured 2026-08-11, same host, back to back:

| | tests | failures | errors |
|---|---|---|---|
| `origin/main`, unchanged | 642 | 7 | 1 |
| with the pins advanced | 644 | 20 | 4 |

Eight tests regress, all in `component_authority` and `overlay_witness`. They
share one cause:

```
ExceptionInfo: Complete Component authority signing input is required
  #:murakumo.component{:reason :invalid-signing-input}
  at murakumo.component_authority/sign-event (component_authority.cljc:111)
```

`sign-event` validates through `component_authority_core`'s **shipped** KIR.
`resources/murakumo/oracle/*.kir.edn` was generated for Profile 5 kir and is
being executed by a newer one. The artifacts and the interpreter are one unit,
and the override splits them.

## Position

**Native qualification is a property we assert about ourselves and cannot
currently check.** Do not quote 33/33 or 35/35 as a property of this repository
until it reproduces from these pins.

The work is a migration, not a pin bump, and it is one change:

1. advance the production `kotoba-kir` pin with the test-only compiler pin —
   they are not independent
2. regenerate every `resources/murakumo/oracle/*.kir.edn` against that pair
   (`clojure -M:test:gen`)
3. confirm the eight `component_authority` / `overlay_witness` tests come back
4. then merge the qualification sweep, so the next regression is loud

Doing (4) before (1)–(3) would land a red test; doing (1)–(3) without (4)
leaves the hole open for the next `d7de271`.

Until then the standing defence is kotoba-native's own
`a-non-tail-record-of-another-schema-is-a-local-not-a-boundary`, which pins the
rule that broke — in that repository, about that rule. It does not protect this
repository's standing in the general property, and nothing here does.

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
