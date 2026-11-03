# Recovery test contract update

The dispatch-marker reuse change added a parameter to `pr-run-decision`, but
its earlier native test file was not updated in that commit. The test calls
and expected result for an exited unit without a marker now match the new
contract, with explicit missing and corrupt marker cases. This was an
integration omission in the preceding change, not evidence that the old test
suite passed.

A local ARM64 native fixture extracted the actual decision and updated test
function; it exited 0. A mutant dropping the marker checks exited 1. The
current cron unity source with the entire updated recovery test file appended
also passed Amu's source check. These checks do not execute the full recovery
suite, real systemd or inference, and the unity dependency graph is historical.

Updated test SHA-256 `95c0329d9dd443b7d5a500acc997e23ce454e170403a5193c3f72c69b901db1c`;
fixture `3078d6b7422d145887bb47e0c2ff822e1b2060e30a5a32adc2f37028b1328ac1`;
signed fixture `4780cab0ff1968651e2dc5c99373cb0383f5443d9a9750c42d31366bee91c8ed`;
signed mutant `0ac347b136b8adade1e85a1699e6a833c7330ae696215b99bcc3a82ee3765571`.
