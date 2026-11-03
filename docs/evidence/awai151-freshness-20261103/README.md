# Native placement observation freshness

Compose the body of `kotoba/fleet_placement_core.kotoba`, following its ns
form, with the `main` form in `main.kotoba` under a new ns exporting `main`.
Compile it with the pinned ARM64 Linux root in `receipt.json`, then execute the
result. The test checks the existing placement groups and verifies that a
future-dated observation yields `eligible/stale`. As a negative control,
replace the revised `fresh?` body by the old `(and has-obs (< (- now-ms at-ms)
ttl-ms))`; the same composed test exits 1. The source and artifact hashes and
test scope are in `receipt.json`.

This is a pure decision check. Placement must still use a trustworthy current
clock, authenticated observations, admission and actual per-host reservation;
a recent observation alone does not prove memory is available. The legacy
CLJK predicate has not been changed and has weaker future-time behavior.
