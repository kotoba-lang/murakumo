# Conditional checkpoint head: local Git observation

On one local ARM64 Mac, a source-unity fixture composed the current signed
checkpoint archive and conditional head functions with the earlier diagnostic
Mac FS2/spawn adapter. It used public synthetic signing material, a private
local bare Git repository, a stubbed privacy check and `file://` instead of
GitHub. This is test-only privileged host authority, not a released runtime.

Two independently prepared signed checkpoints with the same parent competed
for a single pre-provisioned synthetic head. The fixture ran both operations
concurrently: exactly one returned `head/advanced`; the other returned
`head/conflict-or-unknown`. An independent Git read verified the winner's
commit, parent and pointer. A test-only mutant changed a successful push's
return status after the real push: independent readback confirmed the commit
and the candidate returned `unacknowledged`. Separate head refs containing an
extra file or a symlink were refused with the remote ref unchanged. The
immutable archive may have been written before an attempted head update failed.

A separate disposable local bare repository simulated a privileged rewind
between reading the expected head and pushing its child. Ordinary non-force Git
push succeeded because the proposed child remained a descendant of the rewound
ref. This confirms that the candidate's competing-child guarantee depends on a
monotonic, non-deletable ref and is not exact expected-OID CAS under rewind.
GitHub branch protection, lease authorization and sink-side fencing were not
exercised. The fixture does not demonstrate that GitHub accepts a production
credential, that a stale lease cannot win, or that a bot can run autonomously.

`assemble.py` uses the earlier fixture as a dependency prefix and replaces the
privacy check and remote with a local bare repository. `lost-generator.py`
mutates the returned push status; `setup*.py`, `observe*.py` and `build*.py`
are one-off test harnesses, not production tooling, and use fixed local paths.
The previous dependency fixture is described in
[the local archive evidence](../awai150-mac-local-archive-20261103/).

Head source `9afbc5212cef1ec6c27fd31ace6c85bb2e2512493c82b56294af30b95350233f`;
archive source `e1fb3bd28fb8a72386ef2c65c56d19562342b62dd16976e7406f1149c5cda6f2`;
fixture source `89b9c1d3398415511932336537de49d5a5ea7b503118b6c4380690950ccd72bd`;
lost-response fixture source `17252aa8a47bbbfc5bfac59a1023b374bbd317eb0dd3b28635131a834feae957`;
signed fixture `04d8602ac5643141406c69b4c3f0e3d740f644fe50440a33689f7ad898254da1`;
signed lost-response fixture `6bef4310b302949e106dc0dc4e186f64df7577009398d7a379d771b8b7a631ba`;
compiler `920954769b8fdc51cf50dfcab5c44a4e56cf01f261806a153b21488ba81ac4e6`;
diagnostic host packager `cb1c67683776113eddd62bcc817bc3a52f2f6b3ae9984cd74c74e2f6874edfa8`.
