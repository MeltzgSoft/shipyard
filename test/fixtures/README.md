# Test fixtures

Minimal STL files, committed so every test that needs a file on disk has one.
**No test depends on the real model library** - a test that skips when the library
is absent only ever covers the machine that has it.

| file | bytes | what it pins |
|---|---|---|
| `triangle-binary-solid-header.stl` | 134 | Binary STL whose 80-byte header begins with the text `solid`. Detection must key on `size == 84 + 50n` arithmetic, never on the leading text. One triangle with distinctive coordinates (1,2,3 / 4,5,6 / 7,8,9) so a byte-order or offset error is obvious rather than plausible. Its stored face normal is zero, as real exports frequently are. |
| `cube-ascii-crlf.stl` | 2,395 | ASCII STL with CRLF line endings, as found in the wild. Its bytes 80-83 misread as a binary triangle count of **540,028,976**, which would demand 27 GB - the same class of lie as the one real ASCII file in the library, whose header claims 1,814,065,765. A header-trusting parser dies here. |

`triangle-binary-solid-header.stl` was written by hand from the STL specification,
not by `shipyard.fixtures/->binary-stl`. If the encoder and the parser are both
ours, a shared misreading of the format cancels out and the tests still pass; a
file built from the spec breaks that symmetry.

Larger meshes are generated in memory by `shipyard.fixtures` rather than committed.
Expected triangle counts and bounding boxes fall out of the generator, so a
regression cannot quietly rewrite the expectation.
