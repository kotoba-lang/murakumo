# Count unknown retains Flint occupancy

A local Darwin ARM64 native source-unity diagnostic combined the current
`pr-step-range` and Flint busy-marker / release forms with the experimental
FS2 provider. On a private directory, a stubbed count returned unknown (`-1`)
after the actual busy marker had been created. The step returned
`profile/context-budget-unknown-or-insufficient`, the busy marker remained,
the generation-submit stub was not called, and the prior state CID was
unchanged. Removing the positive-count check from count-only release caused
the same diagnostic to fail.

The assembled source SHA-256 was
`347e9f9828c9f65766527eb8c58ed140ecbd440b1e89da14d86310ebe4a3d1e9`
and the signed diagnostic image SHA-256 was
`709eda33be7e7c4fd1455cb39068a639622b440864b9c9810c7f7ae8a17a48d7`.
Build and run scripts are preserved for provenance but reference absolute local
paths and a historical source-unity input in `/private/tmp`; they are not a
self-contained release test. Compiler and runtime were pinned experimental
artifacts. Count, time, unit state, runtime validation and submit were stubs;
other assembled dependencies are historical. This is not an integrated
post-query timing fault, real service or model, process-tree termination,
production durable storage, or a qualified governor.
