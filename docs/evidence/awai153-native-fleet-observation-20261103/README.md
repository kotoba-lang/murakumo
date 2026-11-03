# Native read-only fleet observation probe

On 2026-11-03 the native observer was composed with the current tunnel core
and a test-only privileged `amu-cli-host` process adapter. A single sequential
run against Benjamin used a pinned SSH host key, existing selected identity,
and its Tailscale address. It retrieved all three fixed read-only commands:
`hw.memsize` 17179869184 bytes, swap used 0.00 MiB, and system-wide memory
free 91%. These are only point-in-time host-reported diagnostics, not a
capacity reservation, a freshness guarantee, or permission to run inference.

The native fixture also checked invalid node names, non-Tailscale and
noncanonical addresses, delimiter/traversal paths, an unlisted command, and
missing, nonzero or malformed completion statuses. The checked-in unit fixture
was separately compiled and returned zero, with no network operation. The
integration's SSH child was bounded by the private diagnostic host's five
second timer; its temporary output contains only the three fixed command
outputs. No private-key content was read or copied.

The integration fixture source SHA-256 was
`e431b491b312b882420d396d6fea56eb80474bda02ce187e2b454463d4dbe09c`,
the signed executable was
`8ea7960df5fe0e873c046c5b7468e37765c2400d483877dceeefe13567de7bdb`,
and the diagnostic packager was
`cb1c67683776113eddd62bcc817bc3a52f2f6b3ae9984cd74c74e2f6874edfa8`.
The integration source embeds machine-specific paths and remains task-local.

This does not qualify the production process capability, timeout or capture
limit, process-tree cleanup, remote execution identity, marker authenticity,
or other nodes. The fixed in-band status marker can be forged. The trusted
caller must authorize the node/address pair and key paths, and must provide
the bounded process host; this component is not the resource governor or an
admission controller. No bots, model or periodic observer were started.
