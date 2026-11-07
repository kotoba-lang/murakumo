# Standalone recovery clock guard

The standalone recovery `rf-learn` previously called the quorum proposer even
when the clock returned zero. Although voters reject an invalid initial ballot,
the proposer can retry after a higher promise; later lease admission refusal is
not evidence that no quorum operation occurred. The recovery caller now refuses
unknown/out-of-range time or epoch before token loading or any quorum call.
The predecessor decision independently refuses an invalid clock/epoch; watch
reports that result instead of polling until timeout.

A local signed Darwin native source-unity diagnostic used the current selected
recovery functions and their source test cases. A stub clock returned zero, a
stub quorum returned a sentinel, and the real `rf-learn` refused both current
and previous lease queries. Normal predecessor boundary cases passed; removing
only the pre-quorum guard made the diagnostic fail. The historical unity has
fixture dependencies and stubs and used a previously built compiler and
packager. Source/image SHA-256:
`7a2654f255663fc2b3920fe3b03565fb629b004ceeb90dc13796a46422c31ba8` /
`64e6edd4c983c4b51294cd081f6f7c3f270cca8f529f913668d82c0b5d52ed69`.

No real voter, network, full source-locked build, live recovery, process
supervisor, native governor or model was tested. This does not establish
atomic fencing, resource admission, three-host operation or a GitHub bot turn.
