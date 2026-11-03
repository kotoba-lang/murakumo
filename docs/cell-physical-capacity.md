# Cell physical-pool capacity proposal

AWAI-126 adds `cell-capacity-reason` to compose existing cell eligibility
with a fresh, verified physical-pool snapshot and one resource dimension.
The five integer inputs are qualified capacity, host control reserve, all
committed host and guest reservations, bounded unmanaged usage, and candidate
demand. Missing, negative, unsafe integer, malformed and zero-demand inputs
are refused. Subtraction is checked at each step to avoid overflow. A demand
exactly equal to the remaining capacity can be proposed. This physical-pool
path also requires an observed pressure value of 1, 2 or 4 and a valid
maximum (1, 2, 4, or 0 for the existing default). Missing or unsupported
pressure is refused even though legacy eligibility permits missing pressure.

This is a pure decision boundary, not a live inventory, reservation allocator,
dispatcher or launch permit. The caller must independently authenticate the
snapshot, bind it to the correct physical parent, aggregate all host/VM
reservations, and call this for **every** required resource dimension. The
caller must also enforce consent, model/runtime qualification, network and
deadline policy. A host must commit and fence a durable reservation and
enforce resource limits before starting work. Concurrent proposals do not
reserve capacity. Existing callers are not yet wired to this new function.
In particular, current Mac memory-pressure percentages do not establish the
absolute allocatable RAM or committed reservations required as input.

The source-unity native gate includes cell lifecycle, existing eligibility,
freshness, exact-fit, shortage, malformed and stale/future cases. It compiles
the actual sources and requires a demand-check bypass mutation to fail:

```sh
AMU_ROOT=/path/to/pinned/aarch64-linux-static-root.elf \
TSUKASA_SOURCE=/path/to/pinned/tsukasa \
  ./scripts/check-cell-capacity-native.sh
```

Use verified source revisions; the test gate is not proof of authenticated
telemetry, real reservations, distributed exclusion or successful inference.
