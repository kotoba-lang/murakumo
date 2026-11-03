# Current controller source composition check

The previously recorded source composition predates later changes to the
controller dependencies. The attached test-only assembly refreshes its six
Murakumo files at `fb1488f` and the four `cc-*` cursor functions used by the
controller from itonami-agent `d727010619ec87af997356bb809564e962fc0817`.
The standalone cursor CLI and its node-host dependencies are excluded.
The assembler combines these with a historical unity dependency input, SHA-256
`483f9cd1cd4c103fe476ee3540d2ca7730d44d739333a14f5c14b0c27b29ee80`.
An independent comparison found all 158 selected function forms byte-for-byte
identical to the current source forms. The historical remainder is not fully
provenance-locked and no claim about its correspondence to current main is made.

On one local ARM64 Mac, the bootstrap native compiler `check unity.kotoba`
returned `ok` with a five-second CPU limit. A control with `cc-advance`
removed was rejected with `E2101` for that symbol. No native image was
compiled or executed; this is a source acceptance check, not proof of the
complete runtime, production host, GitHub, inference or safety of deployment.
The assembler is test-only, uses task-specific absolute paths, and requires
the historical temporary input to be adapted when rerun. The exact checked
unity source is retained here independently.

| Input | SHA-256 |
| --- | --- |
| checked unity source | `175f6070fa1113a75615a402480ece943478ff68235b2b3350b402707c65d82a` |
| assembler | `43265249452591afb19f7bd54671f1e785344e66fd9371a18f8db042727eb94c` |
| Mac bootstrap compiler | `920954769b8fdc51cf50dfcab5c44a4e56cf01f261806a153b21488ba81ac4e6` |
| checkpoint source | `e8e524fc95731cc57b29ae7175c02be40b676103b2b8768ab5b08fa7141f08c3` |
| recovery source | `d5a684e06b9577964aa0ae90066df5d2b50413cfd036ec424d09fc219d178ad1` |
| resident source | `d664607aa1367c6977eabb640df78acbede9ea2f1732d380328b92f15f267ace` |
| bridge source | `14f6fb3d2be84cede4de1cc19d03c69acf4f38d795dc9c282b45dba269a042f3` |
| archive source | `e1fb3bd28fb8a72386ef2c65c56d19562342b62dd16976e7406f1149c5cda6f2` |
| controller source | `f6389f7cd0609c491c7b81ece86df38048c250e9a2fc3ba735d122ef0f5f964e` |
| cursor source | `365edbdc592c640e0469668f581a67d41031e5020b4b6dd7b906cd447409adc2` |
