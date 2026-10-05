# CI and build-tool boundaries

## Shell input: GitHub Actions S7630

Put user-controlled workflow strings in `env` and expand them as quoted arguments. GitHub expression substitution happens before shell parsing, so shell quotes surrounding `${{ inputs.version }}` do not make it safe. Validate expected shape and preserve existing tag/environment/permission gates. Avoid `eval`, `sh -c` reconstruction, or passing the data back into another interpreter.

```yaml
env:
  RELEASE_VERSION: ${{ inputs.version }}
run: |
  [[ "$RELEASE_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]
  gh release create "v$RELEASE_VERSION" --verify-tag --generate-notes
```

This is a pattern for an already authorized release workflow, not permission to execute a release. Use explicit repository context when the job has no checkout. Test shell construction with inert metacharacter inputs without invoking a real publication command. [GitHub explanation](https://docs.github.com/en/actions/concepts/security/script-injections).

## Write destinations: S8707

Define the path contract before implementation. For an internal generator confined to a build directory, resolve both root and candidate, verify ancestry before `mkdir` or writes, and reject a symlinked final file that escapes the root. Check the complete final destination as well as the directory. Do not use string-prefix checks (`out-elsewhere` is not inside `out`) or normalize only after creating directories.

Use temporary test directories for valid children, sibling-prefix paths, parent traversal, absolute paths outside the allowed root, and existing escaping symlinks. Leave outside sentinel files untouched. A resolver ancestry check is not sufficient for an actively hostile concurrent filesystem; use OS isolation or descriptor-based operations if that threat actually applies. Do not silently remove legitimate user-selected output capabilities from a general-purpose CLI.

## Temporary state: S5443

On a shared host, use secure temporary-directory APIs and task-owned cleanup, not predictable writable filenames. Inside a disposable container, use a private owned directory for configuration/logs and inspect actual mounts and port bindings. External services may require fixed rendezvous paths; document and verify their isolation instead of hiding the path from the scanner. Do not launch a live service merely to test a static cleanup.

## Regex correctness and cost: S5843, S6353, S8786

Simplify regex alternatives while preserving capture-group meaning, escaping, and accepted syntax. When a pattern is effectively a parser, named patterns or a small tokenizer can be clearer; do not concatenate fragments solely to disguise measured complexity.

Python `\w`/`\d` are Unicode-aware by default. If replacing ASCII ranges, preserve ASCII intent with scoped flags or explicit ranges. A global `re.ASCII` also narrows existing `\s` whitespace and word-boundary behavior; scope it to the identifier when surrounding Unicode behavior must remain unchanged. Test accepted ASCII, rejected non-ASCII identifiers, and previously accepted Unicode whitespace. For unanchored searches, consider retries from every start position as well as backtracking within one match. Use a bounded grammar, appropriate anchoring/tokenization, or other demonstrated linear approach. Possessive quantifiers alone do not prevent all unanchored repeated-start costs.

Test success, malformed suffixes, absent matches, and long near misses with bounded input sizes. Avoid timing-only flaky tests; assert behavior and measure scaling separately when useful. Check the supported Python version before selecting regex features. [Python regex reference](https://docs.python.org/3/library/re.html), [temporary-file reference](https://docs.python.org/3/library/tempfile.html).
