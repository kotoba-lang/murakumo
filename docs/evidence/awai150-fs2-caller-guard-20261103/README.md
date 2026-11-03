# FS2 unknown-state caller guards

The selected checkpoint, archive, resident and inference paths now require an
explicit D=`0` before creating output, staging, temporary request or marker
files. A D result other than `0` or `1` refuses relevant copy, write,
submission or resident decisions. An existing local lease must also contain
valid canonical JSON before a new lease round. The host still has to exclude
concurrent replacement and provide a D implementation which distinguishes
lookup errors from absence. Legacy `cp-exists?` is still used for some positive
checks and the complete call graph has not been certified.

On one Mac, native fixtures extracted the actual changed functions or
submission decision bindings and used a diagnostic Darwin FS2 candidate from
Amu #1796 (`b490eaaf`), whose D returns `?` on EACCES. Python confirmed the
owned test parent had mode 000 and restored it afterward. For a normal absent
path the six helper cases took their normal create branch; on EACCES the
current cases refused and the previous source took the stubbed create branch.
These cases cover content-addressed put/copy, resident save, inference output
save, request/marker preparation and cron marker. Separate extracted resident
fixtures refused unknown head/request and unknown or invalid saved lease;
previous controls went past those checks. A submission fixture returned
`profile/submission-unknown` on EACCES while the previous decision selected a
new unit. These fixtures stub writes, hashes, unit state, leases, authority and
most dependent logic. They do not test a real submission, GitHub, signed
checkpoint, resource governor or full source closure. Not every changed path
was directly exercised, notably full archive restore and materialization.

Current source SHA-256s: `git_checkpoint.kotoba`
`e8e524fc95731cc57b29ae7175c02be40b676103b2b8768ab5b08fa7141f08c3`;
`github_checkpoint_archive.kotoba`
`17f87198dd49e243dc988fe11adb4910d1e6c6f654fedcb5959473d4df4bf0e9`;
`profile_cron_controller.kotoba`
`93823b1d4a31dd2edb369b7f889d3bd5747863190495db31f1916abc62343e6e`;
`profile_recovery.kotoba`
`4630f31c4219197602a3066288116325e147c4064faf28f4e04eac3a051f52ed`;
`profile_resident.kotoba`
`9504c0bbcfe1bdd1341bd6ac6c1dbf4d244f533e2c271e8d3c262b0dff212ce8`.
The signed current/previous helper images were
`67f09a3e19d462dcc3214c4ff4fff7300c8b2cf2effc88ae0541ee875f5b8661` and
`5a27cc667e9fc84ca8192fb0ed9bb8c2792e5c54eef14750318f01176e6d18dc`;
resident images were `4f8fbaa56b82edbe8eb48519ab6819c47d1cfd5418c63df6bb9ead3508c875de`
and `0e90c77a8e39f1928558b261b7dcdac6692f0ad3a2639a02176c39b32e0b0d9c`;
submission images were `25cc1995aab4483fb7f02f16d4940ea6672547d8b79fd89800ac07ea3c616111`
and `91a55e6b409a40e51e830f91b1ffbc9b4e40c2573198c99d9742625d70c7864f`.
The compiler SHA-256 was
`920954769b8fdc51cf50dfcab5c44a4e56cf01f261806a153b21488ba81ac4e6`
and the diagnostic packager was
`4114499dc10bd321070a221f88847b8708565f7d80e87c4ed84b28e599e3c41e`.
The generators reference baseline `c0e1899` and task-local paths; adapt them
for reproduction. The runtime, provenance and limited stubs do not qualify a
production service or every error class.
