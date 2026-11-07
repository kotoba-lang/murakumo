# Block unarchived Flint dispatch in direct recovery

The standalone fenced recovery entry uses `pr-step-bounded` directly and does
not participate in the resident's GitHub archive acknowledgement or state
publication coordinator. Passing it a Flint runtime could therefore bypass
the Flint resident's archive requirement. The recovery dispatch now refuses
the Flint runtime kind with `recovery/flint-archive-gate-required`; a real
archive-gated recovery flow remains to be implemented. Other runtime kinds
retain their existing path. This is a deliberate availability restriction and
is not an archive implementation or distributed fence.

A signed native Mac fixture contains the current `rf-dispatch` form with
stubbed filesystem, quorum, clock, budget, runtime load and inference. It
observed Flint refusal, a non-Flint dispatch, the existing lease change and
unavailable cases, retention and budget refusals, and write failure. A mutant
removing the Flint check failed the same fixture. Fixture source SHA-256 was
`7d6792632ecc4b23f6a310605c002ebab29a42a3027974798a37d533ab28c215`;
signed fixture SHA-256 was
`cbc0f63ed8db6f27d8770a916679e11cf76804a42310f4223623c93d8b5c09a6`.
Selected current fenced recovery forms, resident/recovery forms and historical
dependencies passed the pinned diagnostic Amu source check; composition
SHA-256 was `b1602b7c50424f92537894ef8243dce45409f913b4d86747ca68f878384f4a30`.

This test does not execute a real checkpoint restore, quorum, inference,
GitHub archive, resource governor, complete source lock or selfhost build.
