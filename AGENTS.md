# Agent Client Agent Instructions

Agent Client is a portable Java API, with optional Spring Boot integration, for invoking
autonomous agentic CLIs (Claude Code, Codex, Gemini CLI, Grok, Antigravity, ACP agents and others).
This public repository owns code, tests, Maven builds, releases and public documentation.

## Steward

Planning, design, roadmap and decisions live in the private steward at
`/home/mark/projects/agent-client-steward`; read its `BINDING.md` before planning or executing work.
Do not copy private planning, roadmap, checkpoint or dirty-tree state into public files.

## Build and test

Java 21. Use `./mvnw`, never `mvn`. The normal gate is `./mvnw clean verify`. Run
`./mvnw spring-javaformat:apply` before every commit; the format check is bound to `validate`, so a
violation fails the build before compilation. Live provider tests (`*IT`, tagged `live`) run only
under the opt-in `failsafe` profile and need installed, authenticated CLIs, e.g.
`./mvnw -pl agent-models/agent-claude -am -Pfailsafe -Dit.test=ClaudeAgentMcpIT -Dfailsafe.failIfNoSpecifiedTests=false verify`.
Target a single IT rather than the whole live suite, and keep live qualification separate from the
normal result. For an isolated cache add `-Dmaven.repo.local=/absolute/path`.

## Modules

- `agent-models/agent-model`: `AgentApi` SPI, options, responses, optional `AgentSession` and
  `AgentSessionRegistry`, portable `McpServerDefinition` and catalog.
- `agent-client-core`: `AgentClient` facade, advisors, MCP-name resolution, response accessors.
- `agent-models/agent-<x>`: per-provider model adapters, options and auto-configuration;
  `agent-acp` is the shared ACP lifecycle. `agent-tck` holds contract support.
- `provider-sdks/<x>-cli-sdk`: native CLI clients and command construction. Claude uses the external
  `claude-code-sdk`; ACP providers use the external `acp-core`.
- `agent-starters`, `agent-client-spring-boot-autoconfigure`, `agent-launcher`, `agents/*`: optional
  application integration and examples.

## Architecture

Layers: `AgentClient` facade, `AgentApi` SPI, `XAgentModel` adapter, `provider-sdks` client over a
zt-exec `CLITransport`. Trajectory evidence is exposed only through the facade
(`AgentClientResponse.getPhaseCapture()`), not `AgentApi.call()`. Conversational sessions are an
optional capability: Agent Client owns provider conversation open, prompt, resume where supported,
active-turn cancellation, close, event streaming and translation of session-scoped
`McpServerDefinition` values. Providers that lack a capability must reject it explicitly.

## Hard rules and invariants

- All process execution uses zt-exec; never `ProcessBuilder` or `Runtime.exec()`.
- A provider is three layers: `provider-sdks/<x>-cli-sdk`, `agent-models/agent-<x>`,
  `agent-starters/agent-starter-<x>`. Keep `buildCommand` static and package-private and assert the
  argv in tests; asserting the options object proves nothing about the command line.
- Provider modules may depend on agent-journal capture modules; `agent-models/agent-model` and
  `agent-client-core` may not. They stay journal-free so the API works from plain Java.
- The application owns provider selection, its tool registry, Java callbacks, embedded MCP endpoint
  and workflows. Do not add `LocalTool`, callback registration, a tool bridge or Spring domain
  knowledge here. Adapters never choose themselves or fall back to another provider.
- Keep provider process and resume details behind the provider API. Prove behavior with adapter
  tests and live round trips; configuration acceptance alone is insufficient.
- Follow `/home/mark/projects/agento-forge/guides/java-library-quality.md`. Customized source
  license: see `LICENSE`. Commit messages carry no AI attribution.

## Docs

- Documentation: https://lab.pollack.ai/projects/agent-client (architecture, provider guides,
  configuration reference, tutorials, migration notes).
