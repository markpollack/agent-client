# Agent Client

Agent Client provides a portable Java API and Spring Boot integration for autonomous CLI agents.
The Claude Code, Codex, Gemini CLI, Grok, and Antigravity adapters all pass the provider parity
TCK — ten scenarios each, zero skips, against live CLIs. CI re-verifies the first three on every
commit; Grok and Antigravity are verified against live CLIs but cannot run in CI, because both
authenticate interactively and cache credentials rather than reading an API key. The remaining
adapters carry no parity coverage and are experimental.

See the [Agent Client documentation](https://lab.pollack.ai/projects/agent-client) for architecture,
provider guides, configuration reference, tutorials, and migration notes.

## Maven

```xml
<dependency>
    <groupId>io.github.markpollack</groupId>
    <artifactId>agent-claude</artifactId>
    <version>0.32.0</version>
</dependency>
```

The asynchronous lifecycle and recording API (`agent-client-claude`, `agent-client-codex`, `agent-client-journal`;
see [docs/async-lifecycle.md](docs/async-lifecycle.md)) is on `main` and not yet released. Use `0.32.1-SNAPSHOT` from the
Central snapshot repository `https://central.sonatype.com/repository/maven-snapshots/` with snapshots enabled.

> **Breaking in 0.32.1:** the legacy Claude derived-tracing surface (`traceDir`, `traceContentMode`, `archiveTranscript`,
> `phaseCapture`, `tracePath`, `transcriptPath`, the `trace-*` properties) and `agent-claude`'s Journal 1.x dependencies
> are removed. Use `capture(ClaudeCaptureOptions)` or the lifecycle acquisition surface; see
> [docs/legacy-tracing-removed.md](docs/legacy-tracing-removed.md).

Agent Client requires Java 21. Provider CLIs and authentication are required only when running the
corresponding live integration.

## Build

```bash
./mvnw clean verify
```

The published-consumer gate installs the release-profile/flattened artifacts into an isolated
repository and verifies every runtime-bearing module without a parent or BOM:

```bash
scripts/published-consumer-gate.py
```

Maintainers can run the reproducible offline vulnerability inventory after that gate with a
validated Trivy cache:

```bash
TRIVY_CACHE_DIR=/path/to/validated-trivy-cache scripts/security-scan.sh all
```

## License

Current development is licensed under the [Business Source License 1.1](LICENSE). Version 0.16.0
and earlier remain available under the historical [Apache License 2.0](LICENSE-APACHE.txt); version
0.18.0 was the first BSL release.
