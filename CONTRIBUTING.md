# Contributing

Start with [the contributor guide](docs/contributing.md), [project instructions](AGENTS.md), and [the implementation plan](PLAN.md).

Run `tools/coverage.sh` for a fresh instrumented test run and exact coverage enforcement. Run `tools/consumer_smoke.sh` to verify locally packaged artifacts from a separate project. Neither command publishes externally.

Use `./mill --no-server site.build` to build guides and API documentation. Ordinary tests need no OBS installation. Real OBS checks are opt-in and require a disposable setup; see [compatibility](docs/compatibility.md).

The project uses MIT for first-party code. Preserve third-party attribution and the pinned upstream skill snapshot. Dependency and schema upgrades need provenance review and the same correctness gates as handwritten changes.
