# Agent Client Agent Instructions

This public repository owns code, tests, Maven builds, releases, shipped contracts, and public
documentation. Private planning and control state are authoritative in
`/home/mark/projects/agent-client-steward`; read its `BINDING.md` before planning or executing
work.

Use `./mvnw`, never `mvn`. The normal gate is `./mvnw clean verify`. Run
`./mvnw spring-javaformat:apply` before every commit — the format check is bound to `validate`, so
a violation fails the build before compilation.

All process and subprocess execution MUST use zt-exec. Never use `ProcessBuilder` or
`Runtime.exec()`.

A provider is three layers: `provider-sdks/<x>-cli-sdk` (client, `CLITransport`, discovery, types),
`agent-models/agent-<x>` (model adapter, options, auto-configuration), and
`agent-starters/agent-starter-<x>`. Keep `buildCommand` static and package-private so the argv can
be asserted without the CLI installed — flag mappings drift silently as CLIs evolve, and asserting
the options object proves nothing about the command line.

**Dependency invariant**: provider modules may depend on agent-journal capture modules;
`agent-models/agent-model` and `agent-client-core` may not. Both are journal-free, and that is what
keeps this usable from plain Java without an evidence-ledger dependency.

Follow `/home/mark/projects/agento-forge/guides/java-library-quality.md`. The project uses a
customized source license; see `LICENSE`. Commit messages contain no AI attribution.

Do not copy private planning, roadmap, checkpoint, or dirty-tree state into public files.

For conversational integrations, Agent Client owns semantic provider conversation open, prompt,
resume where supported, active-turn cancellation, close, event streaming, and translation of
ordinary session-scoped `McpServerDefinition` values. Negotiate downstream ACP MCP capabilities
and report unsupported conversation or connection capabilities explicitly. Keep provider process
and resume details behind the provider API. Verify behavior with adapter tests and live round-trip
assertions; configuration acceptance alone is insufficient.

The application chooses the provider and owns its tool registry, Java callback dispatch, embedded
MCP endpoint, domain knowledge, and workflow coordination. Do not add `LocalTool`, callback
registration, a tool bridge, or Spring domain knowledge here. Provider adapters must not choose
themselves or silently fall back to another provider.
