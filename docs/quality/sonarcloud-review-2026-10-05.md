# SonarQube Cloud review — 2026-10-05

## Scope and baseline

Reviewed all **55 OPEN issues** returned by the [requested project filter](https://sonarcloud.io/project/issues?id=worxbend_obs-websocket-client&issueStatuses=OPEN%2CCONFIRMED&s=IMPACT_RANK). No CONFIRMED issues were returned. The API page contained all 55 records and its total matched; security hotspots and resolved issues were outside this request.

Latest analyzed revision at review time: `b994a9c0f8d0cdb15f28618dfb18e4d7da235f76`, dated `2026-10-04T22:45:47+0000`. Our uncommitted generator refactor was newer than that analysis. Locations below are the analyzed locations, not promises of unchanged current line numbers.

Sonar impacts: 1 blocker, 39 high, 11 medium, 4 low. Quality categories: 40 maintainability, 14 security, 1 reliability. Scanner severity is preserved separately from contextual assessment.

## Local disposition

- **46 local fixes**, including defensive hardening of internal build-output paths and container state.
- **7 false-positive candidates**: synthetic credential/redaction cases in tests. Their assertions remain intact.
- **2 intentional exceptions**: a frozen legacy benchmark and the required Xvfb socket path inside an isolated container.

These are local code/review dispositions. No cloud issue status, quality profile, exclusion, or suppression was changed. A new cloud analysis must establish which local fixes are recognized as resolved; do not infer a clean dashboard from this ledger.

## Priorities and caveats

The release command directly interpolated workflow input into shell source. Its required preflight already validates that same version, limiting a straightforward exploit through the current job graph. The sink now uses quoted environment data as well; the job has explicit repository context without relying on a checkout.

The three path findings identify local CLI arguments, despite generic HTTP wording in their flow descriptions. Mill supplies the documentation destination and coverage normally writes under `out/`. Confining these internal writers is reasonable defense against accidental or agent-supplied wrong paths, not evidence of a remotely exposed service. The checks handle existing symlinks; they do not claim race-proof isolation from a concurrent hostile filesystem.

The fixed temporary paths previously lived inside a unique, mount-free disposable container. Private `/run` state improves ownership, while Xvfb's conventional socket remains a documented isolated dependency. No live OBS/Docker run was performed for this cleanup; mocked lifecycle checks do not establish fresh live compatibility.

Refactoring the benchmark would alter what it measures. Synthetic password strings, invalid credential-bearing URLs, HOCON nulls, and redaction labels do not warrant deletion or randomization. Recommend narrow evidence-backed dispositions for those nine findings after maintainer review.

## Validation approach

Preserve the complete generated catalog byte-for-byte and use full golden fixtures. Run Scala module tests, exact fresh coverage, formatting, Python tests, documentation compilation and site links. The Python review additionally compares old/new Markdown rendering with identical navigation, checks escaping output symlinks, exercises long regex near misses, and mocks container validation/cleanup. Global skill guidance is structurally validated and independently evaluated against actual repository examples.

The versioned prevention skills are [sonar-issue-prevention](../../skills/sonar-issue-prevention/SKILL.md) and [sonarcloud-triage](../../skills/sonarcloud-triage/SKILL.md); standalone global copies are installed in the user's Codex skills directory. Skills improve future decisions but do not replace CI or guarantee zero findings.

## Complete issue ledger

### 1. githubactions:S7630 — Local fix

[Issue AaEJGJlUDsOV8zejauwY](https://sonarcloud.io/project/issues?id=worxbend_obs-websocket-client&open=AaEJGJlUDsOV8zejauwY) · `.github/workflows/release.yml:105` · security blocker.

Sonar: inputs.version is vulnerable to script injection: values of inputs are provided by whoever triggers the workflow. Change this workflow to not use user-controlled data directly in a run block, for example by assigning this expression to an environment variable.

Action: Pass release version through quoted environment expansion and set explicit GH_REPO; preserve required semver/tag preflight.

### 2. scaladre:S3776 — Intentional exception

[Issue AaEJGJl1DsOV8zejauwZ](https://sonarcloud.io/project/issues?id=worxbend_obs-websocket-client&open=AaEJGJl1DsOV8zejauwZ) · `bench/src/com/worxbend/obs/websocket/client/bench/LegacyJsonValue.scala:21` · maintainability high.

Sonar: Refactor this method to reduce its Cognitive Complexity from 22 to the 15 allowed.

Action: Preserve the verbatim pre-optimization codec so the A/B benchmark remains a valid baseline.

### 3. scaladre:S1192 — Local fix

[Issue AaEJGJUoDsOV8zejauwR](https://sonarcloud.io/project/issues?id=worxbend_obs-websocket-client&open=AaEJGJUoDsOV8zejauwR) · `codegen/test/src/com/worxbend/obs/websocket/client/codegen/GenerateSuite.scala:264` · maintainability high.

Sonar: Define a constant instead of duplicating this literal "(1 << 0)" 3 times.

Action: Use a meaningful owner-local constant; test expectations remain independent of production constants.

### 4. scaladre:S1192 — Local fix

[Issue AaEJGJUoDsOV8zejauwS](https://sonarcloud.io/project/issues?id=worxbend_obs-websocket-client&open=AaEJGJUoDsOV8zejauwS) · `codegen/test/src/com/worxbend/obs/websocket/client/codegen/GenerateSuite.scala:266` · maintainability high.

Sonar: Define a constant instead of duplicating this literal "(One | Two)" 3 times.

Action: Use a meaningful owner-local constant; test expectations remain independent of production constants.

### 5. scaladre:S1192 — Local fix

[Issue AaEJGJhkDsOV8zejauwW](https://sonarcloud.io/project/issues?id=worxbend_obs-websocket-client&open=AaEJGJhkDsOV8zejauwW) · `core/test/src/com/worxbend/obs/websocket/client/SessionSuite.scala:416` · maintainability high.

Sonar: Define a constant instead of duplicating this literal "in-flight requests" 3 times.

Action: Use a meaningful owner-local constant; test expectations remain independent of production constants.

### 6. scaladre:S1192 — Local fix

[Issue AaEJGJhkDsOV8zejauwV](https://sonarcloud.io/project/issues?id=worxbend_obs-websocket-client&open=AaEJGJhkDsOV8zejauwV) · `core/test/src/com/worxbend/obs/websocket/client/SessionSuite.scala:490` · maintainability high.

Sonar: Define a constant instead of duplicating this literal "WebSocket closed" 3 times.

Action: Use a meaningful owner-local constant; test expectations remain independent of production constants.

### 7. scaladre:S1192 — Local fix

[Issue AaEJGJhkDsOV8zejauwX](https://sonarcloud.io/project/issues?id=worxbend_obs-websocket-client&open=AaEJGJhkDsOV8zejauwX) · `core/test/src/com/worxbend/obs/websocket/client/SessionSuite.scala:981` · maintainability high.

Sonar: Define a constant instead of duplicating this literal "interrupted-registration" 3 times.

Action: Use a meaningful owner-local constant; test expectations remain independent of production constants.

### 8. scaladre:S1192 — Local fix

[Issue AaEJGJa0DsOV8zejauwT](https://sonarcloud.io/project/issues?id=worxbend_obs-websocket-client&open=AaEJGJa0DsOV8zejauwT) · `protocol/test/src/com/worxbend/obs/websocket/client/protocol/ProtocolSuite.scala:16` · maintainability high.

Sonar: Define a constant instead of duplicating this literal "Malformed JSON" 3 times.

Action: Use a meaningful owner-local constant; test expectations remain independent of production constants.

### 9. scaladre:S1192 — Local fix

[Issue AaEJGJcbDsOV8zejauwU](https://sonarcloud.io/project/issues?id=worxbend_obs-websocket-client&open=AaEJGJcbDsOV8zejauwU) · `sttp/test/src/com/worxbend/obs/websocket/client/transport/sttp/SttpTransportSuite.scala:51` · maintainability high.

Sonar: Define a constant instead of duplicating this literal "Incoming message exceeds configured byte limit" 4 times.

Action: Use a meaningful owner-local constant; test expectations remain independent of production constants.

### 10. scaladre:S1192 — Local fix

[Issue AaEHsCJXIX282Dw4QB5f](https://sonarcloud.io/project/issues?id=worxbend_obs-websocket-client&open=AaEHsCJXIX282Dw4QB5f) · `codegen/test/src/com/worxbend/obs/websocket/client/codegen/GenerateSuite.scala:77` · maintainability high.

Sonar: Define a constant instead of duplicating this literal "Catalog.scala" 4 times.

Action: Use a meaningful owner-local constant; test expectations remain independent of production constants.

### 11. scaladre:S1192 — Local fix

[Issue AaEHsCJXIX282Dw4QB5g](https://sonarcloud.io/project/issues?id=worxbend_obs-websocket-client&open=AaEHsCJXIX282Dw4QB5g) · `codegen/test/src/com/worxbend/obs/websocket/client/codegen/GenerateSuite.scala:156` · maintainability high.

Sonar: Define a constant instead of duplicating this literal "requests/Nested.scala" 4 times.

Action: Use a meaningful owner-local constant; test expectations remain independent of production constants.

### 12. scaladre:S1067 — Local fix

[Issue AaEHsCOjIX282Dw4QB5l](https://sonarcloud.io/project/issues?id=worxbend_obs-websocket-client&open=AaEHsCOjIX282Dw4QB5l) · `core/src/com/worxbend/obs/websocket/client/ObsConfig.scala:54` · maintainability high.

Sonar: Reduce the number of conditional operators (4) used in the expression (maximum allowed 3).

Action: Name the URI scheme/host and forbidden-component predicates while preserving validation order.

### 13. scaladre:S3776 — Local fix

[Issue AaEHsCQ0IX282Dw4QB5n](https://sonarcloud.io/project/issues?id=worxbend_obs-websocket-client&open=AaEHsCQ0IX282Dw4QB5n) · `core/src/com/worxbend/obs/websocket/client/SampledSubscription.scala:44` · maintainability high.

Sonar: Refactor this method to reduce its Cognitive Complexity from 21 to the 15 allowed.

Action: Separate window collection, key-defect handling, and publication while preserving deadlines, closure, and drops.

### 14. scaladre:S1066 — Local fix

[Issue AaEHsCQ0IX282Dw4QB5m](https://sonarcloud.io/project/issues?id=worxbend_obs-websocket-client&open=AaEHsCQ0IX282Dw4QB5m) · `core/src/com/worxbend/obs/websocket/client/SampledSubscription.scala:83` · maintainability medium.

Sonar: Merge this "if" statement with the nested one.

Action: Combine the publication guard after separating window collection.

### 15. scaladre:S1192 — Local fix

[Issue AaEHsCSCIX282Dw4QB5p](https://sonarcloud.io/project/issues?id=worxbend_obs-websocket-client&open=AaEHsCSCIX282Dw4QB5p) · `core/test/src/com/worxbend/obs/websocket/client/SessionSuite.scala:278` · maintainability high.

Sonar: Define a constant instead of duplicating this literal "http://localhost" 4 times.

Action: Use a meaningful owner-local constant; test expectations remain independent of production constants.

### 16. scaladre:S1192 — Local fix

[Issue AaEHsCSCIX282Dw4QB5o](https://sonarcloud.io/project/issues?id=worxbend_obs-websocket-client&open=AaEHsCSCIX282Dw4QB5o) · `core/test/src/com/worxbend/obs/websocket/client/SessionSuite.scala:830` · maintainability high.

Sonar: Define a constant instead of duplicating this literal "cleanup defect" 3 times.

Action: Use a meaningful owner-local constant; test expectations remain independent of production constants.

### 17. scaladre:S1192 — Local fix

[Issue AaEHsCLfIX282Dw4QB5h](https://sonarcloud.io/project/issues?id=worxbend_obs-websocket-client&open=AaEHsCLfIX282Dw4QB5h) · `protocol/test/src/com/worxbend/obs/websocket/client/protocol/NestedFieldsSuite.scala:35` · maintainability high.

Sonar: Define a constant instead of duplicating this literal "outer.inner.leaf" 4 times.

Action: Use a meaningful owner-local constant; test expectations remain independent of production constants.

### 18. scaladre:S1192 — Local fix

[Issue AaEHsCLsIX282Dw4QB5i](https://sonarcloud.io/project/issues?id=worxbend_obs-websocket-client&open=AaEHsCLsIX282Dw4QB5i) · `protocol/test/src/com/worxbend/obs/websocket/client/protocol/ObjectModelSuite.scala:133` · maintainability high.

Sonar: Define a constant instead of duplicating this literal "scene-id" 4 times.

Action: Use a meaningful owner-local constant; test expectations remain independent of production constants.

### 19. scaladre:S1192 — Local fix

[Issue AaEHsCL9IX282Dw4QB5j](https://sonarcloud.io/project/issues?id=worxbend_obs-websocket-client&open=AaEHsCL9IX282Dw4QB5j) · `protocol/test/src/com/worxbend/obs/websocket/client/protocol/WorkflowSuite.scala:11` · maintainability high.

Sonar: Define a constant instead of duplicating this literal "data:image/png;base64,AQID" 3 times.

Action: Use a meaningful owner-local constant; test expectations remain independent of production constants.

### 20. scaladre:S2068 — False-positive candidate

[Issue AaEHsCTmIX282Dw4QB5r](https://sonarcloud.io/project/issues?id=worxbend_obs-websocket-client&open=AaEHsCTmIX282Dw4QB5r) · `server/test/src/com/worxbend/obs/websocket/client/server/ConfigurationSuite.scala:62` · security medium.

Sonar: "password" detected here, make sure this is not a hard-coded credential.

Action: Preserve the synthetic authentication/rejection/redaction fixture; no real credential is represented.

### 21. scaladre:S2068 — False-positive candidate

[Issue AaEHsCTmIX282Dw4QB5s](https://sonarcloud.io/project/issues?id=worxbend_obs-websocket-client&open=AaEHsCTmIX282Dw4QB5s) · `server/test/src/com/worxbend/obs/websocket/client/server/ConfigurationSuite.scala:96` · security medium.

Sonar: "password" detected here, make sure this is not a hard-coded credential.

Action: Preserve the synthetic authentication/rejection/redaction fixture; no real credential is represented.

### 22. scaladre:S1192 — Local fix

[Issue AaEHsCSvIX282Dw4QB5q](https://sonarcloud.io/project/issues?id=worxbend_obs-websocket-client&open=AaEHsCSvIX282Dw4QB5q) · `server/test/src/com/worxbend/obs/websocket/client/server/MainSuite.scala:5` · maintainability high.

Sonar: Define a constant instead of duplicating this literal "http://127.0.0.1:8080/docs" 3 times.

Action: Use a meaningful owner-local constant; test expectations remain independent of production constants.

### 23. scaladre:S1192 — Local fix

[Issue AaEHsCN1IX282Dw4QB5k](https://sonarcloud.io/project/issues?id=worxbend_obs-websocket-client&open=AaEHsCN1IX282Dw4QB5k) · `sttp/test/src/com/worxbend/obs/websocket/client/transport/sttp/SttpOptionsSuite.scala:20` · maintainability high.

Sonar: Define a constant instead of duplicating this literal "X-Test" 4 times.

Action: Use a meaningful owner-local constant; test expectations remain independent of production constants.

### 24. python:S1192 — Local fix

[Issue AaEHsCGCIX282Dw4QB5a](https://sonarcloud.io/project/issues?id=worxbend_obs-websocket-client&open=AaEHsCGCIX282Dw4QB5a) · `tools/build_site.py:36` · maintainability high.

Sonar: Define a constant instead of duplicating this literal '.html' 4 times.

Action: Use a meaningful owner-local constant; test expectations remain independent of production constants.

### 25. python:S5843 — Local fix

[Issue AaEHsCGCIX282Dw4QB5b](https://sonarcloud.io/project/issues?id=worxbend_obs-websocket-client&open=AaEHsCGCIX282Dw4QB5b) · `tools/build_site.py:51` · maintainability medium.

Sonar: Simplify this regular expression to reduce its complexity from 21 to the 20 allowed.

Action: Use named inline-token patterns and retain scanner ordering, capture meaning, and escaping; differential-check rendering.

### 26. python:S3776 — Local fix

[Issue AaEHsCGCIX282Dw4QB5c](https://sonarcloud.io/project/issues?id=worxbend_obs-websocket-client&open=AaEHsCGCIX282Dw4QB5c) · `tools/build_site.py:103` · maintainability high.

Sonar: Refactor this function to reduce its Cognitive Complexity from 41 to the 15 allowed.

Action: Extract focused operations for rendering, coverage checks, link resolution, or container lifecycle; preserve outputs and failure checks.

### 27. pythonsecurity:S8707 — Local fix

[Issue AaEHsCDDIX282Dw4QB5Z](https://sonarcloud.io/project/issues?id=worxbend_obs-websocket-client&open=AaEHsCDDIX282Dw4QB5Z) · `tools/check_coverage.py:141` · security high.

Sonar: Path Traversal via faulty LLM-supplied CLI arguments in check_coverage.main()

Action: Resolve the full write destination and require it under repository out/ before side effects; reject escaping symlinks. CLI callers are local, not HTTP.

### 28. python:S8786 — Local fix

[Issue AaEHsCG6IX282Dw4QB5d](https://sonarcloud.io/project/issues?id=worxbend_obs-websocket-client&open=AaEHsCG6IX282Dw4QB5d) · `tools/real_obs_smoke.py:127` · reliability medium.

Sonar: Simplify this regular expression to reduce its runtime, as it has super-linear performance due to backtracking.

Action: Prevent retries inside a failed digit run with a leading digit-boundary guard; test a long near miss and valid logs.

### 29. python:S5778 — Local fix

[Issue AaEHsCHJIX282Dw4QB5e](https://sonarcloud.io/project/issues?id=worxbend_obs-websocket-client&open=AaEHsCHJIX282Dw4QB5e) · `tools/test_site.py:76` · maintainability medium.

Sonar: Refactor this exception test to have only one invocation possibly throwing an exception.

Action: Prepare changed diagram lines before entering assertRaises, leaving only the intended operation inside.

### 30. python:S1192 — Local fix

[Issue AaEHLMf-CA3zdn3bf0ks](https://sonarcloud.io/project/issues?id=worxbend_obs-websocket-client&open=AaEHLMf-CA3zdn3bf0ks) · `tools/build_site.py:123` · maintainability high.

Sonar: Define a constant instead of duplicating this literal '</ul>' 3 times.

Action: Use a meaningful owner-local constant; test expectations remain independent of production constants.

### 31. python:S3776 — Local fix

[Issue AaEHLMd4CA3zdn3bf0kj](https://sonarcloud.io/project/issues?id=worxbend_obs-websocket-client&open=AaEHLMd4CA3zdn3bf0kj) · `tools/check_coverage.py:79` · maintainability high.

Sonar: Refactor this function to reduce its Cognitive Complexity from 28 to the 15 allowed.

Action: Extract focused operations for rendering, coverage checks, link resolution, or container lifecycle; preserve outputs and failure checks.

### 32. pythonsecurity:S8707 — Local fix

[Issue AaEHLMfyCA3zdn3bf0kp](https://sonarcloud.io/project/issues?id=worxbend_obs-websocket-client&open=AaEHLMfyCA3zdn3bf0kp) · `tools/doc_snippets.py:10` · security high.

Sonar: Path Traversal via faulty LLM-supplied CLI arguments in doc_snippets.main()

Action: Resolve the full write destination and require it under repository out/ before side effects; reject escaping symlinks. CLI callers are local, not HTTP.

### 33. pythonsecurity:S8707 — Local fix

[Issue AaEHLMfyCA3zdn3bf0kq](https://sonarcloud.io/project/issues?id=worxbend_obs-websocket-client&open=AaEHLMfyCA3zdn3bf0kq) · `tools/doc_snippets.py:28` · security high.

Sonar: Path Traversal via faulty LLM-supplied CLI arguments in doc_snippets.main()

Action: Resolve the full write destination and require it under repository out/ before side effects; reject escaping symlinks. CLI callers are local, not HTTP.

### 34. python:S3776 — Local fix

[Issue AaEHLMgJCA3zdn3bf0kw](https://sonarcloud.io/project/issues?id=worxbend_obs-websocket-client&open=AaEHLMgJCA3zdn3bf0kw) · `tools/real_obs_smoke.py:74` · maintainability high.

Sonar: Refactor this function to reduce its Cognitive Complexity from 26 to the 15 allowed.

Action: Extract focused operations for rendering, coverage checks, link resolution, or container lifecycle; preserve outputs and failure checks.

### 35. python:S5443 — Local fix

[Issue AaEHLMgJCA3zdn3bf0kx](https://sonarcloud.io/project/issues?id=worxbend_obs-websocket-client&open=AaEHLMgJCA3zdn3bf0kx) · `tools/real_obs_smoke.py:106` · security high.

Sonar: Make sure publicly writable directories are used safely here.

Action: Move configuration, credentials, logs, and PID state to private mode-0700 /run/obs-disposable inside the owned container.

### 36. scaladre:S2068 — False-positive candidate

[Issue AaEHLMhrCA3zdn3bf0k_](https://sonarcloud.io/project/issues?id=worxbend_obs-websocket-client&open=AaEHLMhrCA3zdn3bf0k_) · `core/test/src/com/worxbend/obs/websocket/client/SessionSuite.scala:643` · security medium.

Sonar: Review this hard-coded URL, which may contain a credential.

Action: Preserve the synthetic authentication/rejection/redaction fixture; no real credential is represented.

### 37. scaladre:S1192 — Local fix

[Issue AaEHLMiCCA3zdn3bf0lC](https://sonarcloud.io/project/issues?id=worxbend_obs-websocket-client&open=AaEHLMiCCA3zdn3bf0lC) · `integration/test/src/com/worxbend/obs/websocket/client/integration/RealObsSuite.scala:26` · maintainability high.

Sonar: Define a constant instead of duplicating this literal "Disposable OBS is required" 3 times.

Action: Use a meaningful owner-local constant; test expectations remain independent of production constants.

### 38. scaladre:S1192 — Local fix

[Issue AaEHLMg6CA3zdn3bf0k5](https://sonarcloud.io/project/issues?id=worxbend_obs-websocket-client&open=AaEHLMg6CA3zdn3bf0k5) · `sttp/test/src/com/worxbend/obs/websocket/client/transport/sttp/SttpClientSuite.scala:62` · maintainability high.

Sonar: Define a constant instead of duplicating this literal "No connection acquired" 4 times.

Action: Use a meaningful owner-local constant; test expectations remain independent of production constants.

### 39. scaladre:S1192 — Local fix

[Issue AaEHLMgwCA3zdn3bf0k4](https://sonarcloud.io/project/issues?id=worxbend_obs-websocket-client&open=AaEHLMgwCA3zdn3bf0k4) · `codegen/test/src/com/worxbend/obs/websocket/client/codegen/GenerateSuite.scala:62` · maintainability high.

Sonar: Define a constant instead of duplicating this literal "requests/Zed.scala" 4 times.

Action: Use a meaningful owner-local constant; test expectations remain independent of production constants.

### 40. scaladre:S1192 — Local fix

[Issue AaEHLMgwCA3zdn3bf0k3](https://sonarcloud.io/project/issues?id=worxbend_obs-websocket-client&open=AaEHLMgwCA3zdn3bf0k3) · `codegen/test/src/com/worxbend/obs/websocket/client/codegen/GenerateSuite.scala:198` · maintainability high.

Sonar: Define a constant instead of duplicating this literal "obs-codegen-test" 3 times.

Action: Use a meaningful owner-local constant; test expectations remain independent of production constants.

### 41. scaladre:S1192 — Local fix

[Issue AaEHLMhrCA3zdn3bf0lA](https://sonarcloud.io/project/issues?id=worxbend_obs-websocket-client&open=AaEHLMhrCA3zdn3bf0lA) · `core/test/src/com/worxbend/obs/websocket/client/SessionSuite.scala:227` · maintainability high.

Sonar: Define a constant instead of duplicating this literal "Must not reach callback" 4 times.

Action: Use a meaningful owner-local constant; test expectations remain independent of production constants.

### 42. scaladre:S2068 — False-positive candidate

[Issue AaEHLMhrCA3zdn3bf0k9](https://sonarcloud.io/project/issues?id=worxbend_obs-websocket-client&open=AaEHLMhrCA3zdn3bf0k9) · `core/test/src/com/worxbend/obs/websocket/client/SessionSuite.scala:257` · security medium.

Sonar: "password" detected here, make sure this is not a hard-coded credential.

Action: Preserve the synthetic authentication/rejection/redaction fixture; no real credential is represented.

### 43. scaladre:S2068 — False-positive candidate

[Issue AaEHLMhrCA3zdn3bf0k-](https://sonarcloud.io/project/issues?id=worxbend_obs-websocket-client&open=AaEHLMhrCA3zdn3bf0k-) · `core/test/src/com/worxbend/obs/websocket/client/SessionSuite.scala:281` · security medium.

Sonar: Review this hard-coded URL, which may contain a credential.

Action: Preserve the synthetic authentication/rejection/redaction fixture; no real credential is represented.

### 44. scaladre:S3776 — Local fix

[Issue AaEHLMh3CA3zdn3bf0lB](https://sonarcloud.io/project/issues?id=worxbend_obs-websocket-client&open=AaEHLMh3CA3zdn3bf0lB) · `protocol/src/com/worxbend/obs/websocket/client/protocol/JsonValue.scala:19` · maintainability high.

Sonar: Refactor this method to reduce its Cognitive Complexity from 22 to the 15 allowed.

Action: Extract JSON object/array readers; preserve token order, depth, duplicate-key rejection, and numeric precision.

### 45. scaladre:S2068 — False-positive candidate

[Issue AaEHLMgVCA3zdn3bf0kz](https://sonarcloud.io/project/issues?id=worxbend_obs-websocket-client&open=AaEHLMgVCA3zdn3bf0kz) · `server/test/src/com/worxbend/obs/websocket/client/server/ConfigurationSuite.scala:14` · security medium.

Sonar: "PASSWORD" detected here, make sure this is not a hard-coded credential.

Action: Preserve the synthetic authentication/rejection/redaction fixture; no real credential is represented.

### 46. scaladre:S2068 — False-positive candidate

[Issue AaEHLMgVCA3zdn3bf0k0](https://sonarcloud.io/project/issues?id=worxbend_obs-websocket-client&open=AaEHLMgVCA3zdn3bf0k0) · `server/test/src/com/worxbend/obs/websocket/client/server/ConfigurationSuite.scala:83` · security medium.

Sonar: "PASSWORD" detected here, make sure this is not a hard-coded credential.

Action: Preserve the synthetic authentication/rejection/redaction fixture; no real credential is represented.

### 47. scaladre:S1192 — Local fix

[Issue AaEHLMggCA3zdn3bf0k2](https://sonarcloud.io/project/issues?id=worxbend_obs-websocket-client&open=AaEHLMggCA3zdn3bf0k2) · `server/test/src/com/worxbend/obs/websocket/client/server/EndpointsSuite.scala:39` · maintainability high.

Sonar: Define a constant instead of duplicating this literal "http://localhost" 4 times.

Action: Use a meaningful owner-local constant; test expectations remain independent of production constants.

### 48. scaladre:S1192 — Local fix

[Issue AaEHLMhFCA3zdn3bf0k6](https://sonarcloud.io/project/issues?id=worxbend_obs-websocket-client&open=AaEHLMhFCA3zdn3bf0k6) · `sttp/test/src/com/worxbend/obs/websocket/client/transport/sttp/SttpTransportSuite.scala:66` · maintainability high.

Sonar: Define a constant instead of duplicating this literal "WebSocket closed" 3 times.

Action: Use a meaningful owner-local constant; test expectations remain independent of production constants.

### 49. python:S3776 — Local fix

[Issue AaEHLMf-CA3zdn3bf0ku](https://sonarcloud.io/project/issues?id=worxbend_obs-websocket-client&open=AaEHLMf-CA3zdn3bf0ku) · `tools/build_site.py:213` · maintainability high.

Sonar: Refactor this function to reduce its Cognitive Complexity from 23 to the 15 allowed.

Action: Extract focused operations for rendering, coverage checks, link resolution, or container lifecycle; preserve outputs and failure checks.

### 50. python:S7494 — Local fix

[Issue AaEHLMd4CA3zdn3bf0kk](https://sonarcloud.io/project/issues?id=worxbend_obs-websocket-client&open=AaEHLMd4CA3zdn3bf0kk) · `tools/check_coverage.py:143` · maintainability low.

Sonar: Replace dict constructor call with a dictionary comprehension.

Action: Use a dictionary comprehension with the existing overwrite behavior.

### 51. python:S9409 — Local fix

[Issue AaEHLMfyCA3zdn3bf0km](https://sonarcloud.io/project/issues?id=worxbend_obs-websocket-client&open=AaEHLMfyCA3zdn3bf0km) · `tools/doc_snippets.py:16` · maintainability low.

Sonar: Use "list.extend()" instead of consecutive "append()" calls.

Action: Group the three related header lines with list.extend.

### 52. python:S6353 — Local fix

[Issue AaEHLMfyCA3zdn3bf0kn](https://sonarcloud.io/project/issues?id=worxbend_obs-websocket-client&open=AaEHLMfyCA3zdn3bf0kn) · `tools/doc_snippets.py:21` · maintainability low.

Sonar: Use concise character class syntax '\w' instead of '[A-Za-z_0-9]'.

Action: Use scoped ASCII identifier matching; preserve surrounding Unicode whitespace semantics and add regression cases.

### 53. python:S6353 — Local fix

[Issue AaEHLMfyCA3zdn3bf0ko](https://sonarcloud.io/project/issues?id=worxbend_obs-websocket-client&open=AaEHLMfyCA3zdn3bf0ko) · `tools/doc_snippets.py:23` · maintainability low.

Sonar: Use concise character class syntax '\w' instead of '[A-Za-z_0-9]'.

Action: Use scoped ASCII identifier matching; preserve surrounding Unicode whitespace semantics and add regression cases.

### 54. python:S5443 — Local fix

[Issue AaEHLMgJCA3zdn3bf0kv](https://sonarcloud.io/project/issues?id=worxbend_obs-websocket-client&open=AaEHLMgJCA3zdn3bf0kv) · `tools/real_obs_smoke.py:38` · security high.

Sonar: Make sure publicly writable directories are used safely here.

Action: Move configuration, credentials, logs, and PID state to private mode-0700 /run/obs-disposable inside the owned container.

### 55. python:S5443 — Intentional exception

[Issue AaEHLMgJCA3zdn3bf0ky](https://sonarcloud.io/project/issues?id=worxbend_obs-websocket-client&open=AaEHLMgJCA3zdn3bf0ky) · `tools/real_obs_smoke.py:109` · security high.

Sonar: Make sure publicly writable directories are used safely here.

Action: Keep the Xvfb-mandated /tmp/.X11-unix/X99 socket inside the unique mount-free container; document why it differs from shared host storage.
