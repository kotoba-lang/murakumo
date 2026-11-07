# Bound resident request and archive acknowledgement reads

The resident previously loaded its request and archive acknowledgement files
with `cp-read` before checking their contents. A large file could therefore
consume the resident arena before the existing request-size or JSON checks.
It now uses the existing bounded FS2 size/range helper with limits of 8192
bytes for requests and 4096 bytes for acknowledgements. Missing requests retain
the previous consumed request ID; empty, oversized or unreadable present
requests refuse. An absent or unreadable configured acknowledgement continues
to block the archive gate.

Selected source with historical dependencies passed pinned Amu `check`. A
signed native Mac fixture using actual local diagnostic FS2 and JSON helpers
accepted valid normal and exactly 8192-byte requests, refused 8193-byte and
empty present requests, and preserved the prior request ID when absent.
Fixture source SHA-256:
`0de50b5cb48faa24f813a3d0c419c275d3f95ff429bdfa4ba4af0b07fca8c319`;
signed image SHA-256:
`649530b1b2b1807887879a434e74b903f5c4fa76540c939c676c2cf0ba125243`.
A separate signed fixture rechecked the Flint public/runtime pins and accepted
matching acknowledgement while rejecting wrong-root, expired and missing
acknowledgements, with a stubbed clock. Its source SHA-256 was
`f13516c91becfabc6cb030693b65d9ecd5b1370966bbd8067cc400795ad99ebc`.

The provider must enforce range bounds, and the host must exclude same-size
concurrent changes or path replacement. The test did not inject short or
failed range responses, cover oversized acknowledgement behavior independently,
run the full resident cycle, contact GitHub, authenticate a server or qualify
the governor or selfhost. Other resident input paths were not audited here.
