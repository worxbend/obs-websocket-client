---
name: mill-scala
description: Build, test, document, and package Scala 3 JVM projects with Mill. Use when editing a Mill build or adapting direct-style Scala, Ox, or Tapir examples to Mill, especially for this Java 25 OBS client library.
---

# Mill Scala

Use Mill as the project's authoritative build. Preserve the user's Scala, JVM,
dependency, and product choices when adapting examples from other build tools.

## Inspect before changing

Read applicable `AGENTS.md`, `PLAN.md`, the build definition, wrapper version,
and formatter configuration. Check module layout and existing task names with
the pinned wrapper. Prefer `./mill` over a global executable.

This project's agreed baseline is Scala 3 only, Java 25, Ox concurrency, sttp
WebSockets, and jsoniter-scala. The base package is
`com.worxbend.obs.websocket.client`. The product is a reusable library; keep the
Tapir/Netty sync sample and optional integrations outside its required dependencies.

Load VirtusLab's `direct-style-scala` skill and the chapters relevant to the
implementation when available. Its language, concurrency, and resource guidance
complements this skill. Adapt sbt and Scala CLI tooling examples to Mill; do not
create a second maintained build. If the user requires upstream skill installation
before Scala work and installation is blocked, finish independent documentation
and report that prerequisite explicitly.

## Build and toolchain

- Pin a stable Mill wrapper and application Scala version. Verify requested
  latest versions against official release metadata; skip prereleases unless
  requested. Do not silently downgrade Scala to work around dependencies.
- Use the APIs documented for that Mill release. Modern examples use
  `build.mill`, `package build`, `ScalaModule`, and `mvnDeps`; old `build.sc`
  and `ivyDeps` snippets may require migration.
- Use native module source/test directories unless an existing project has
  deliberately chosen a different layout. Add modules only when they have a
  concrete dependency boundary or implementation.
- Configure Java 25 for the Mill process and each relevant compile/test/run
  process. Verify the resolved JVM paths/version; selecting the shell's Java
  alone may not control Mill's forked processes. Scala CLI JVM directives do
  not configure a Mill build.
- Keep shared Scala/JVM/dependency versions in one place. Confirm compiler
  plugins and jsoniter macros work with the chosen compiler.
- Use Scala 3 indentation syntax in project Scala and build code. Keep public
  signatures explicit without inventing task result types from an older Mill API.

For dependency upgrades, inspect the resolved graph and canonical Maven metadata
for the exact `_3` artifact. Do not infer compatibility from a library's name or
the fact that an artifact exists in a local cache.

## Generate an HTTP starter

Inspect Adopt Tapir's supported builders. If Mill is unavailable, generate into a
temporary directory with a supported builder, then transfer its sources,
dependencies, resources, and test settings into Mill. Preserve OxStack, Netty
sync, Swagger enabled, metrics disabled, and Jsoniter for this project.

Remove migrated source directives and unsupported build files from the final
project. Verify the main class and resource loading. Do not leave documentation
that tells users to run `scala-cli` or sbt for project tasks.

## Work through the task graph

Resolve actual tasks before invoking unfamiliar commands. For modules that exist,
use `./mill <module>.compile`, `./mill <module>.test`, and
`./mill <module>.run`. Run a server in a controllable foreground session and
check its endpoint before stopping it cleanly.

Keep code generation deterministic and offline during ordinary compilation:
pin schemas, declare inputs, write derived output to Mill-managed destinations,
and test changes with independent fixtures. Do not fetch moving upstream branches
as an implicit compile side effect.

Use the configured Mill formatting integration. Run relevant compile/tests after
code changes; broaden checks when the change affects shared modules. Report the
commands actually executed, their outcomes, and blocked checks separately.

## Library, documentation, and coverage gates

Keep protocol models, session logic, and sttp implementation separable from the
sample server. Publish source and API documentation artifacts alongside library
JARs when release configuration is ready.

For this project's requested 100% coverage, verify Scoverage compatibility early.
Measure executed statements and branches per production module and in aggregate,
including generated production code. Fail on missing/stale reports or totals below
100%; compare counts without rounding. A tooling limitation is a blocker to the
claim, not permission to hide code with broad exclusions.

Compile documentation snippets, build the static microsite, and check links and
assets under `/obs-websocket-client/`. CI should build PR documentation without
deploying it; Pages deployment uses the tested artifact and explicit deployment
permissions. Badges must represent measured results or clearly labeled targets.

## Completion and boundaries

A plan, wrapper file, or dependency download does not prove the project builds.
Distinguish authored, installed, compiled, tested, published, and deployed states.
If tools cannot reach the network or write their required directories, preserve
useful local work and state the unfinished steps. Do not change sandbox settings,
delete existing user configuration, or substitute an unrelated effect runtime.

## References

- [Mill Scala guide](https://mill-build.org/mill/scalalib/intro.html)
- [Mill installation and IDE setup](https://mill-build.org/mill/cli/installation-ide.html)
- [Mill Scoverage](https://mill-build.org/mill/contrib/scoverage.html)
- [VirtusLab direct-style Scala skill](https://github.com/VirtusLab/scala-skill)
- [Scala releases](https://www.scala-lang.org/download/all.html)

Consult documentation for the pinned release; unversioned links can change.
