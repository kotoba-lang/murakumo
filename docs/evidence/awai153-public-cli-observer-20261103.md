# macOS public CLI observer check

The native observation bridge was composed with the Amu macOS host API and
its dependency sources on one local Apple Silicon Mac. With the pinned public
CLI below, `check` returned `ok`, but `run` of that composition returned exit
133 without an observation. A zero-argument native control returned exit 0;
a separate guest invoking wire 20 with an invalid request also returned 133.
The latter does not distinguish a missing capability from request rejection.
The examined host packager source explicitly reserves its private AMUH service
for the CLI host, not ordinary guest images. These observations do not prove
that every macOS CLI version behaves identically.

Another copied CLI image at `/private/tmp/codex-main-amu-cli` refused both
commands earlier with `EPROVENANCE full compiler digest mismatch`. It was not
used for the successful source check or the exit-133 observation; a relocated
binary without its expected pinned compiler is not evidence about the bridge.
All local runs were bounded and no service or model was started.

The observer now also requires an AMUH process reply with exactly three
fields: success status, zero exit and zero signal. A native fixture accepted
the valid tuple and rejected nonzero status, exit, signal, and a truncated
reply. The full local native observation still succeeded using the separate
diagnostic host packager. This does not make that host suitable for untrusted
guests or qualify the public CLI capability.

The next integration requires a scoped native host capability for the fixed
observation commands and private captures, explicit grant/ABI qualification,
and process-lifetime bounds. The private CLI host service must not be exposed
to arbitrary guests as a shortcut. No persistent deployment was performed.

| Artifact | SHA-256 |
| --- | --- |
| pinned public CLI | `6e7dc223795919581cdfc9128344948dfaddec58e9573bef9632923538f33a2a` |
| composed source used for CLI check/run | `46d0c76a04148e856393f49f644283f41c8b2b95e4131ab2da194da6c3c906ab` |
| current macOS bridge source | `d8c334d2bce70f5e5cda522e15ebe03af4c952d69c1f58af0018e7e0405a4f6f` |
| bridge test source | `0b1065c3947e79adf41addb70b9a91eb948568e8fdce4d8fd65a73dcf9211789` |
| signed status unit fixture | `1cc2ed227435832944dc77133b15b27f4f9b2b89397401f431e3a771153ed59b` |
| signed local observation fixture | `e6ba2940c2184163e24db9a9bef0d3a173c0fff71c1d0d95744e0449f26e9960` |
