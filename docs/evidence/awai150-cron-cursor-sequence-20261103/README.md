# Controller-to-cursor sequence with diagnostic FS2

The attached test-only native fixture composes the current controller's path,
dispatch marker, cycle and advance functions with `cc-advance` extracted from
itonami-agent `d727010619ec87af997356bb809564e962fc0817`. On one local ARM64
Mac it uses the newer diagnostic Darwin FS2 provider and a private temporary
directory. FS2 existence, reads, byte writes, rename and sync are real.
Scheduling, trusted head, archive, inference, cursor serialization, digest,
clock and authorization are substituted with fixtures. A deliberately injected
`?` for the pending-path lookup after the simulated turn represents an unknown
provider response; it is not an induced kernel error or a concurrent writer.

- The successful simulated turn wrote its marker and advanced the cursor.
- A pre-existing pending file refused before the simulated turn.
- An injected unknown after the simulated turn left the cursor unchanged and
  retained the dispatch marker; a repeat also refused without another turn.
- A control using the old existence predicate reported completion and advanced
  under the same injected unknown response.

The fixture calls `nc-cycle` directly and its own `main` exits zero so the
observer can inspect the result; it does not test the controller CLI exit
status. It does not perform a real GitHub archive, lease, model turn, crash,
power loss, concurrent mutation, process supervision or fleet admission. The
diagnostic provider and the complete assembled controller remain unqualified.

Fixture source SHA-256:
`e3069058a6018cc7b648f846a0cf10ddf098be554cccb070742c41d161ef513d`.
Signed candidate and old-control fixture SHA-256:
`ad594538ddb8bb0afc7f4995f18f68ac19232a7921cefc33c243d4b3dfc07fd2`,
`de534e77f915194fc17a2f1be0e9ee49daced744d1dc1aeb9e59c2c448191e06`.
The controller and cursor source hashes and their previous function-level test
are recorded in [the composition evidence](../awai150-cursor-integration-20261103/README.md).
