# Native GraphQL head candidate: local transport fixture

On a local ARM64 Mac, a source-unity fixture used the actual native request,
base64, archive and GraphQL head candidate, but replaced the GH transport with
a test-only Python process. That process validated the request shape and used
`git update-ref` with an expected old OID against a task-owned local bare
repository. Privacy was stubbed, the remote was `file://`, and a privileged
Mac diagnostic FS2/spawn adapter ran the child processes. No credential, real
GitHub API or bot was used by this fixture.

Two synthetic signed checkpoint successors concurrently targeting the same
head produced exactly one `head-cas/advanced` and one conflict/unknown; an
independent Git read matched the winning tip and parent. When the fake API
committed successfully but returned failure, independent readback produced an
`unacknowledged` success. A separate test deliberately rewound the local test
ref to a different commit between the candidate's fetch and the fake atomic
update: the expected-OID update was refused and the candidate returned
conflict/unknown. This does not test ABA (rewind to the same expected OID).

A [separate operator-side test](../awai150-github-graphql-cas-20261103/) used
the real GitHub API and observed expected-head conflict on a temporary ref;
it did not invoke this native candidate. The proposed production GH and Git
credential delivery, real GraphQL integration, lease authority, ref rollback
protection, process lifecycle and fleet operation remain unqualified. A
verified pointer is not authority to execute or publish effects.

`assemble.py` uses the earlier head-restore unity fixture; `fake.py` and the
other Python files are one-off test harnesses and not production tooling. Paths
are fixed test paths. Candidate source SHA-256
`2250bbed2be57d6e1fb40bb3a122a7f3a9a66e0e807881589f739f93703db921`;
fixture source `b42f94730fe801e233d0df55d651c363d357f1a2db2817fed65ee62b648e298c`;
signed fixture `d467aa8b80505493aecf31d51a950e2b430401a62930e70a0339ada983e16eef`;
compiler `920954769b8fdc51cf50dfcab5c44a4e56cf01f261806a153b21488ba81ac4e6`;
diagnostic host packager `cb1c67683776113eddd62bcc817bc3a52f2f6b3ae9984cd74c74e2f6874edfa8`.
