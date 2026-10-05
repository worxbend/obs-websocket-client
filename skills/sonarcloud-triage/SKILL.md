---
name: sonarcloud-triage
description: Audit SonarQube Cloud or SonarQube findings against the analyzed revision and current code, exhaust pagination, distinguish actionable defects from justified exceptions, and verify authorized fixes. Use for Sonar issue URLs, quality-gate failures, and static-analysis triage.
---

# SonarCloud triage

Treat a scanner finding as evidence to investigate, not a verdict or permission to modify the cloud project.

## Retrieve the complete scope

Identify the supplied server, project key, branch or pull request, status filters, and latest analyzed revision. Preserve that scope; do not silently substitute the default branch. Prefer an available connector or the server's documented read-only API. A JavaScript-only UI can be inspected through its API or an authorized browser session.

Fetch every page and reconcile returned issue counts with the reported total. If the service limits results, partition by supported disjoint filters and deduplicate by issue key. Report partial access as partial review. Never expose tokens in URLs, output, files, or commits. Open/confirmed issues, security hotspots, resolved issues, and new-code-only findings are separate scopes.

## Review against actual code

Compare the analyzed commit with HEAD and local edits before trusting line numbers. Record issue key, rule, impact, source location, verdict, and proposed action. Read surrounding logic and callers, especially for taint findings. Read [references/triage-pitfalls.md](references/triage-pitfalls.md) when security or benchmark findings need context.

Use explicit verdicts: actionable defect, maintainability improvement, context-dependent hardening, false positive, accepted intentional design, or stale finding. Preserve both the scanner's severity and your practical priority; explain material differences. Trace the source, sink, trust boundary, privileges, and any effective earlier validation. A label saying HTTP or LLM input does not establish that the CLI has an HTTP caller.

## Fix only the authorized scope

A request to inspect is a review. A request to fix authorizes relevant code changes, not remote issue transitions or broad exclusions. Preserve contracts, deterministic generated output, benchmark baselines, and security regression tests. Do not hide strings, weaken rules, delete tests, refresh snapshots blindly, or introduce a runtime/framework solely to lower a score.

Group fixes by behavior and verify the affected build/test gates. If coverage uses a source fingerprint, finish source and documentation edits before collecting final evidence. A passing local test run does not establish a passing cloud scan. Report local fixes separately from cloud resolution; only claim the latter after inspecting a subsequent analysis of the pushed revision.

Provide a concise prioritized summary and a complete issue ledger for larger audits. Link issues and evidence. Changes to cloud dispositions, workflow dispatches, releases, and publication require authorization for those actions.
