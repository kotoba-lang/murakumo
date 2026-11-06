# Recovery predecessor lease shape

Standalone recovery previously compared the learned predecessor with the
expected bytes and checked epoch, class and slot, but did not validate the
whole lease. For example, a matching canonical value lacking `node` and with
an expired `until` could be classified as a takeover. The predecessor check
now requires one canonical six-field object, a nonempty valid lease node,
bounded lease values and a nonnegative revision before evaluating takeover.
Invalid shape reports an explicit refusal in watch mode.

On a local Darwin ARM64 diagnostic compiler/packager, a source-unity fixture
ran the selected actual recovery functions and test cases. Missing node, empty
node and negative revision were refused; valid held and expired predecessors
retained their boundary behavior. The earlier zero-clock guard was also
exercised. Replacing only the predecessor check with its previous implementation
made the same fixture fail; bypassing the pre-quorum clock guard also failed.
Source/image SHA-256:
`6023f79ad8ab3c72ad6cd78b08fa15d2d267a9a31206417fa6f422027923749e` /
`0acd7a1c6a27f3538135b809d92d060441cb0028289c7c49915d0b10c509b315`.

The fixture composes selected functions with historical dependencies and uses
stubbed clock and quorum calls. It does not verify live voters, distributed
fencing, a source-locked release compiler, the native governor or bot execution.
