# Agent Client 0.32.0

**Every provider's Spring Boot starter now provides an `AgentClient.Builder`. Also: per-request environment for
one-shot Claude calls, a standalone Codex CLI streaming transport, and a build that enforces five quality gates.**

## Fixed

- **Spring Boot starters for Codex, Gemini, Grok, Junie and Qwen Code now provide an `AgentClient.Builder`**
  (`d3b7b24`). `AgentClientAutoConfiguration` creates the builder only when an agent bean exists, but it declared no
  ordering, so Spring Boot could evaluate it before these providers' auto-configurations had registered their
  agent. Their starters then started the application without a builder, and anything injecting
  `AgentClient.Builder` failed. It now runs after every provider auto-configuration. Claude, Amp, Amazon Q and
  Antigravity were not affected.
- **The Amazon Q starter now includes the AgentClient auto-configuration** (`307c273`). `agent-starter-amazon-q`
  depended only on `agent-amazon-q`, so it provided an agent but no `AgentClient.Builder`. It now declares
  `agent-client-spring-boot-autoconfigure`, like the other starters, which adds `agent-client-core` and
  `agent-client-spring-boot-autoconfigure` to its dependencies.

Both fixes are guarded by tests: one fails if a provider auto-configuration is missing from the ordering, and
another fails if any starter lacks the auto-configuration module.

## What changed

- **Per-request environment for one-shot Claude calls** (`2655def`). `ClaudeAgentModel` now merges the request's
  `AgentOptions` environment variables over the model's configured defaults before it starts the Claude CLI. The
  model's defaults are not modified, the inherited `PATH` is kept, and a request with no environment still runs.
- **Codex CLI streaming** (`528e656`). `codex-cli-sdk` gains a streaming transport:
  - `CodexClient.stream(String sessionId, String prompt, Consumer<String> observer)` streams one turn, starting a
    new thread or resuming the given one, and delivers each JSON line to the observer as it arrives;
  - `CLITransport.stream(...)` is the transport entry point beneath it;
  - `ExecuteOptions` adds `environment(Map)` and `configOverrides(Map)`, with matching getters, for the child
    process environment and `-c` configuration overrides.

  These methods are additions only; existing `CodexClient` and `ExecuteOptions` callers are unaffected.
- **Build quality gates.** `./mvnw verify` now fails on:
  - JaCoCo line and branch floors for each published module's own tests;
  - SpotBugs findings ranked 1–9 ("scariest" and "scary"), plus named multithreaded-correctness patterns at any
    rank;
  - Error Prone findings in main sources, compiled with `-Werror`;
  - PMD rules and CPD duplication, with a decreasing allowance for the Claude execution path;
  - ArchUnit package-cycle rules in 14 modules.

  New own-module tests cover `agent-amazon-q`, `agent-amp`, the Spring Boot auto-configuration and `agent-tck`.
  The gates are build-time only and add nothing to any published module's dependencies.
- **CI.** The provider parity workflow now skips a provider whose credential is not configured, and says so in its
  summary, instead of failing (`82d9c64`).

## Upgrading

Drop-in for most applications. Bump the `agent-client` coordinates to 0.32.0; no dependency version changes.

- **`DeprecatedPropertyMigrator` registration.** The migrator that rewrites deprecated `agent-client` properties now
  implements Spring Boot's `org.springframework.boot.EnvironmentPostProcessor`, and its `META-INF/spring.factories`
  key changed to match. This matters only to an application that refers to the migrator by type or replaces its
  registration; ordinary auto-configuration users see no change.
- **`AgentModel` stays.** The deprecated `AgentModel` interface and every provider model that implements it are
  unchanged in 0.32.0. It remains deprecated for removal and will be removed in a later release.

## Maven Central

`io.github.markpollack` version `0.32.0` for all 34 published modules (`agent-client-parent`, `agent-client-core`,
`agent-client-spring-boot-autoconfigure`, `agent-model`, `agent-tck`, `agent-launcher`, the provider CLI SDKs,
agent models and starters). Each carries its consumer-rooted CycloneDX SBOM (`-cyclonedx.json`) as a signed
attachment.
