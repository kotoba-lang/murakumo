# Inference unit reuse requires the dispatch marker

A new inference submission writes and syncs a dispatch marker before launching
its unit. Previously, a later running or exited unit could be reused solely
because its request bytes matched, even when that marker was missing or
corrupt. The decision now requires both presence and exact marker content for
reuse. A leftover marker still blocks a new submission. The separate pending
state reconciliation already checked the same marker contents.

On a local ARM64 Mac, a native source-unity fixture tested the actual decision
function with running and exited units, matching request and marker, missing
and mismatching marker, and new-unit conditions. It exited 0; a mutant that
removed the marker requirement exited 1. The full current cron source was
also assembled against historical dependencies and passed Amu's source check.
This fixture supplies booleans and does not exercise real systemd, filesystem
marker reads, process failure, inference or a distributed lease. The host
still owns cache isolation and the unit namespace.

Recovery source SHA-256 `d5a684e06b9577964aa0ae90066df5d2b50413cfd036ec424d09fc219d178ad1`;
fixture `c596394990b91a39930a0f1591e962149bb44487213b289e1bc0e516b2e3943c`;
signed fixture `c7efce5ceb445f2791030261c86b401d433cf10b017bf68471843f37bc0ec735`;
signed mutant `58c47964924edd6a0f69d1fa870ea25018daca84d9728ff7ef16ec1c4dd4f282`.
The compiler and diagnostic host are those of the earlier
[bridge lock observation](../awai150-standalone-bridge-lock-20261103/).
