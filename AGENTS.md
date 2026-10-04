# Project guidance

This repository is a Scala 3 OBS WebSocket client library. Read `PLAN.md` for
the agreed scope and implementation gates.

Use the project skill at `skills/mill-scala/SKILL.md` for Mill build, test,
documentation, and packaging work. It is versioned here because this environment's
standard skill directories are read-only. This link makes it available as project
guidance; it is not a claim of global Codex skill installation.

Before writing Scala, read `skills/direct-style-scala/SKILL.md` and its relevant
chapters. The complete upstream skill is available locally; its revision and
file hashes are recorded in `skills/upstream-scala.json`. This is a project-local
snapshot, not a global Codex marketplace installation. Preserve upstream files
and adapt their build-tool examples to Mill using the companion project skill.

Agreed choices: Mill, latest stable Scala 3, Java 25, Ox, sttp, jsoniter-scala,
and package `com.worxbend.obs.websocket.client`. No effect-system runtime.

The public repository URL supplied by the user is
`https://github.com/worxbend/obs-websocket-client`. Do not substitute the current
filesystem directory's misspelling or a different authenticated GitHub username.

Keep planned functionality, measured test results, and published artifacts
clearly distinguished in documentation. Coverage must reach 100% statements and
branches under the measurement rules in `PLAN.md`; do not invent passing badges.
