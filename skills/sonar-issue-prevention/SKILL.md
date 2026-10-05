---
name: sonar-issue-prevention
description: Prevent recurring Sonar findings while writing or refactoring Scala libraries and tests, Python build tooling, and GitHub Actions. Check complexity, repeated semantic literals, regex behavior, output paths, temporary-state ownership, test precision, and shell injection while preserving existing contracts.
---

# Prevent recurring Sonar issues

Apply the relevant checks as code is written, then review the changed diff before completion. This skill supports automatic selection for applicable work; it is not a substitute for CI or a guarantee that all future findings disappear.

Read only the relevant reference:

- Scala, tests, or control-flow refactoring: [maintainability.md](references/maintainability.md).
- GitHub Actions, Python CLI paths, temporary storage, or regexes: [tooling-safety.md](references/tooling-safety.md).

Preserve the repository's language version, build wrapper, effect model, public contracts, and chosen test/coverage gates. Prefer small named operations and explicit data flow. Do not replace a local maintainability change with a framework migration, universal abstraction, or new dependency.

Use the project's configured rule thresholds, not hard-coded universal limits. Sonar rule IDs below are traceability anchors from observed findings, not a claim that local code was reanalyzed. No broad exclusions, `NOSONAR`, renamed secret literals, or deleted assertions as a shortcut. A justified false positive deserves an evidence-based disposition; changing a cloud issue is a separate authorized action.

Before finishing, inspect the formatted code, run focused behavioral checks and required repository gates, and distinguish verified local results from a later scanner result. Preserve complete generated-output fixtures during generator refactors. In fingerprinted coverage workflows, finish all measured source/doc edits before the final run. Do not automatically run live integration services or publish artifacts to satisfy a code-quality check.
