# ADR-261027: Build and ship the core from the kotoba library, not from duplicate sources

- Status: accepted
- Date: 2026-10-27
- Resolves: the blocker named in JUN-380 — "how do builds/installers/tests
  obtain library sources before the duplicated core is removed"

## Context

`kotoba.murakumoCore` (kotoba #908/#909) carries **84 of murakumo's 227
namespaces plus 53 tests** — the host-free, dependency-closed cut recorded in
`murakumo-core-cut.json`. Owner decision (2026-10-27, option B) kept murakumo's
own copies, so the same code exists in two places today. Removing the murakumo
copies breaks **27 files that name the old paths**:

- `scripts/build-node-cli.sh` and `scripts/web-bundle.sh` copy the sources;
- `release/node.mjs` embeds whole namespaces as `loadString` strings;
- `install-*-image-worker.cljk` and `src/murakumo/edge_install.cljk` ship them
  to devices;
- `scripts/tasks.edn` runs kbb with classpath `src:test`;
- `cljk-origin.edn`, docs, and one test fixture.

Two constraints shape the answer. Fleet CI ships this repository **without west
siblings**, so a sibling checkout cannot be the only mechanism. And the
pass-5 rewrite convention is already fixed: a dependency key stays, its value
points at `kotoba-lang/kotoba` with `:deps/root` into `lib/`.

## Decision

1. **One canonical source, one pin.** The library copy
   (`lib/kotoba.murakumoCore` in kotoba-lang/kotoba) is canonical at a pinned
   revision recorded in **one** file at the repo root, `murakumo-kotoba-pin.edn`
   (`:git/sha`, archive `:digest`, `:date`). Nothing else names a revision.

2. **One resolver per surface.** `scripts/kotoba_lib_root.cljk` resolves the
   library root in this order: `$KOTOBA_LIB` → the `../kotoba` sibling checkout
   → **fail**, printing the pin sha so a missing checkout is an environment gap
   with instructions, not a silent wrong-path build.

   - **tools.deps / kbb**: one coordinate per library root —
     `io.github.kotoba-lang/kotoba {:git/url "https://github.com/kotoba-lang/kotoba.git"
     :git/sha "<pin>" :deps/root "lib/kotoba.murakumoCore"}` in `deps.edn`,
     byte-copied into `nbb.edn` (nbb reads neither `deps.edn` nor `bb.edn`;
     "a coordinate they cannot see is not a declaration"). If kbb's tools.deps
     subset turns out not to implement `:deps/root`, the kbb classpath falls
     back to the resolver and the gap is recorded as a tool gap to close — not
     as a reason to keep the duplicates.
   - **`scripts/tasks.edn` classpaths** and the file-copy steps in
     `build-node-cli.sh` / `web-bundle.sh` take the root from the resolver;
     no script hand-writes a path to a core namespace.
   - **`release/node.mjs`'s embedded namespaces are build output**, generated
     from the library sources at build time by the same resolver. They stop
     being hand-maintained source the moment the cutover lands.
   - **Device shippers** (`install-*-image-worker.cljk`, `edge_install.cljk`)
     fetch the pinned kotoba archive **by digest** at pack time and ship from
     the unpacked library root. No fetch at device time; an archive whose
     digest does not match the pin is refused.

3. **Cutover order — each step green before the next.**

   1. Land the pin file, the resolver and the rewritten build scripts while
      the duplicates still exist. This step is behavior-identical; the node
      CLI bundle, web bundle and a device image are byte-compared against the
      duplicate-source build.
   2. Re-sync check: diff murakumo's 84 files against the library copies and
      carry any murakumo-side delta into a kotoba `lib/` PR first. After
      cutover the library is canonical: edits to those namespaces land in
      kotoba and arrive here as a pin bump.
   3. Delete the 84 duplicates (and the fixture fix) in one PR. The moved
      tests run as the library's oracle suites; murakumo's shell tests keep
      running here.

4. **Scope** is exactly the cut in `murakumo-core-cut.json`. Host-side code,
   `grant.*`, `aiueos.provider.device` and the shell that requires the core
   stay in this repository (the edge is one-way: shell → core).

## Consequences

- One source, one pin, one resolver: CI without west siblings works (git
  coordinate with `:deps/root`, or the digest-pinned archive), and there is a
  single place to change when the acquisition mechanism evolves.
- Editing a core namespace becomes a kotoba PR plus a pin bump here — slower
  than editing in place, and the price of not maintaining two truths.
- The 27 path-naming files shrink to the resolver call sites; `release/node.mjs`
  stops carrying source strings that can drift from `src/`.
