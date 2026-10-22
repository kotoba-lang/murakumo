# AGENTS.md — murakumo

The workspace rules in com-junkawasaki/root `AGENTS.md` apply: CDCI is
stopped, and when it restarts it runs Amu selfhost and Kotoba native gates only.

## CI/CD: no JVM, no nbb

Owner decision 2026-10-22: the JVM and nbb (Node.js) are not used, so they are
not part of CI/CD (Linear JUN-290). `.github/workflows/leftover-jvm.yml` (JVM library tests,
`workflow_dispatch`) and `.github/workflows/system-one.yml` (System One
through nbb) were removed.

- Do not add `setup-java`, `setup-clojure`, `clojure`, nbb, babashka or
  GraalVM steps to a workflow, and do not keep them as a fallback. A check that
  needs something the native route cannot do yet is a blocker to record in
  Linear, not permission to bring a host runtime back.
- A native gate is an Amu selfhost build: the pinned root compiler
  (kotoba-lang/amu `seed/bootstrap/aarch64-linux-static-root.elf`) compiles a
  Kotoba module in the seed subset into a loaderless static image whose result
  is checked, together with a negative control that must fail.
- `.github/workflows/ci.yml` remains. It starts no JVM or nbb, but its
  jobs run on Node.js, which root `AGENTS.md` also retires; it is disabled
  like the rest of CDCI and is tracked separately.

### What CI no longer covers

The leftover JVM library tests (kwik/BouncyCastle overlay, KIR `:gen`,
oracle parity) and the System One offline harness tests are not run by CI.
They are migration inventory (JUN-290).
