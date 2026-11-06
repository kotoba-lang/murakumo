# OCI model staging lexical overlap correction

`md-path?` accepts a trailing slash, but the model asset `ta-overlap?`
predicate previously appended another slash to each input before checking
ancestor relationships. For example, `/layout/` and `/layout/out` were
incorrectly reported as disjoint. Several staging, verification and publication
preflights depend on this predicate. It now removes a trailing slash before
comparison and treats empty or root paths as overlapping. Paths that only
share a partial component, such as `/layout` and `/layout-other`, remain
separate. Callers must still validate inputs and exclude filesystem aliases,
symlink races, concurrent writers and replacement independently.

A local signed Darwin native source-unity diagnostic ran the actual selected
predicate and its new test cases. Parent/child in both orders, trailing-slash
equality, root, empty input and disjoint cases passed; a separate image built
with the old predicate failed. Source/image SHA-256:
`a9e8f52e3cc133b9b932f3fe10dc0ebfa203f5c18079a9d2102e4658de874247` /
`04127693403d8ecee09676731d068b7f87c0faf729ae94253fbcb655c0532401`.
This is a pure lexical test using previously built diagnostic tools, not an
OCI copy, publication, process isolation, native governor qualification, or
authorization to run a model.
