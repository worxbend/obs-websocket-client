# Releases

The project is licensed under MIT. Bundled third-party specifications and skills retain their own attribution and licenses. No public library artifact or documentation deployment has been performed by this implementation run.

## Pre-release checklist

- Compile every module with warnings as errors and verify formatting.
- Run protocol fixtures, deterministic concurrency faults, real-socket tests, and HTTP/Swagger checks.
- Regenerate bindings twice and compare output; review the catalog inventory.
- Pass exact 100% statement and branch coverage for every production module and aggregate.
- Run disposable real OBS connection, authentication, scene/event, and restart checks; record versions.
- Build source, documentation, and binary artifacts; compile a consumer against locally published artifacts.
- Verify Maven namespace ownership, version/tag agreement, dependency licenses, and changelog.
- Build and link-check documentation under `/obs-websocket-client/`; verify Pages after deployment.
- Obtain an explicit release instruction before uploading a library release.

## Publication status

The provisional group is `com.worxbend.obs.websocket.client`. Artifacts are `obs-websocket-client-protocol_3`, `obs-websocket-client-core_3`, and the backend adapters `obs-websocket-client-sttp_3`, `obs-websocket-client-okhttp_3`, `obs-websocket-client-zio_3`, `obs-websocket-client-fs2_3`, and `obs-websocket-client-pekko_3`; an application declares exactly one backend adapter, with `sttp` as the default. Local snapshot publication is for development only. Do not copy these as published Maven Central coordinates.

Maven credentials, signing, repository branch protection, and Pages enablement require the corresponding account setup. Release automation must fail before external publication when any gate fails; it must never overwrite an existing version.


## Executable preflight

`./mill --no-server release.packageReport` builds the seven libraries' binary, source and
Scaladoc JARs and writes their SHA-256 hashes under `out/release/`.
`./mill --no-server release.dependencyReport` inventories resolved runtime dependencies,
their hashes and cached POM license declarations. Missing declarations are
explicitly marked for review; this is not a license compatibility audit or a
vulnerability scan. The server's dependencies are listed separately.

`tools/consumer_smoke.sh` publishes to a temporary local Maven repository and
compiles and runs a separate consumer declaring every backend artifact.
It checks transitive dependency resolution and Java 25 execution for each adapter, then removes
the temporary repository. It performs no remote publication.

`OBS_RELEASE_VERSION` selects a stable `x.y.z` version; otherwise artifacts use
`0.1.0-SNAPSHOT`. The manual `Release preflight and publish` workflow defaults
to preflight only. It checks exact fresh coverage, formatting, generation,
packaging, a separate consumer, and documentation before its publication step.
A successful publish also creates the GitHub Release for the tag with generated
notes; that runs as a separate job after publication, so its failure does not
fail the publication. Publication requires selecting `publish`, running from
the matching `vVERSION` tag, and configuring the `maven-central` environment's
credentials and signing
key. Set `MAVEN_NAMESPACE_VERIFIED` to `com.worxbend.obs.websocket.client` only
after ownership has been verified with Sonatype. The workflow has been authored;
remote publishing and signing have not been exercised.

Publication stays in the same validated workspace and is disabled by default.
The `maven-central` environment must additionally define `OBS_COMPATIBILITY_SHA` equal to the exact release commit SHA
and `SCHEMA_LICENSE_REVIEWED` equal to the revision in `protocol-spec/provenance.json`.
These acknowledgements must follow the real OBS verification and schema licensing review;
the workflow does not manufacture that evidence.
