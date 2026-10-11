# Generator golden fixtures

The nine files in `expected/` were captured from the unmodified generator at
commit `b994a9c0f8d0cdb15f28618dfb18e4d7da235f76`, before the interpolation-template
refactor. They exercise all seven output families using the adjacent synthetic
schema, overrides, and checksum provenance.

The Python renderer tests compare the full output map against these files. Preserve
whitespace, including trailing spaces in empty parameter documentation and the
newline after every file. These fixtures establish behavior preservation, not
live OBS compatibility.

The reviewed RequestExecutor seam intentionally updates only `RequestApi.scala`:
`RequestApi` retains its request method and categories while each category depends
on the smaller executor contract. The full-catalog hash oracle likewise updates
only that path; all other pinned output bytes remain frozen.

For an intentional output change, review the full diff and generated API before
updating the expected files. Do not regenerate them merely to silence a failure.
