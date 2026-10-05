# Maintainability without contract drift

## Complexity: S3776, S1067, S1066

Extract coherent operations, not arbitrary blocks chosen only to beat a metric. A parser can dispatch to object/array readers; a CLI can orchestrate input validation, execution, and reporting. Keep error context and branch order visible.

Name long predicates by the invariant they check. Preserve short-circuit evaluation, side effects, and error precedence. Merge nested guards only when evaluating the inner condition at the combined point has identical behavior.

For Scala concurrent loops, identify scope/resource ownership before extracting functions. Preserve cancellation checks, deadlines, channel termination, pending state, and loss accounting. Return immutable collection outcomes or ADTs where helpful; do not move method-local loop state into shared fields. Keep the existing effect model and compiler options. A historical benchmark kept for A/B measurement is not a normal refactoring target.

## Repeated literals: S1192

Use a named constant when occurrences mean the same thing: fixture IDs, artifact paths, expected stable diagnostics, and endpoint defaults. Scope it to the smallest owning suite/module. Do not create global constants for unrelated equal strings or force every two-character delimiter behind a name.

Tests should retain independent expectations. A test-local expected diagnostic is reasonable; importing the production constant to assert against itself can mask a contract change. Preserve descriptive scenario names. Do not extract synthetic credentials just to evade a credential rule.

## Test precision: S5778, S2068

Prepare arguments outside an exception assertion so the block contains only the intended throwing operation. Assert relevant error context when it matters. Keep redaction, invalid-URI, Unicode-authentication, and missing-password cases. Use clearly synthetic, test-only values that do not authenticate to real services; document this when a security rule misclassifies them.

## Small Python improvements: S7494, S7500, S9409

Prefer a dictionary comprehension when it transforms keys or values, preserving duplicate-key and ordering semantics. If an iterable already contains the desired key/value pairs, `dict(pairs)` is clearer than an identity comprehension. Avoid cycling between S7494 and S7500: combine adjacent construction and transformation into one meaningful comprehension when appropriate. Use `extend` for a coherent consecutive group of list elements. Avoid these rewrites when they obscure evaluation order or comprehension side effects.

## Verification

Choose tests for the changed behavior: parser malformed/duplicate/deep inputs, timeout/closure/overflow boundaries, output byte equality, exception provenance, or dictionary overwrite behavior. Keep exact project coverage gates intact; never add exclusions to hide branches introduced by refactoring.
