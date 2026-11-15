# Agent Client 1.0.0 (draft)

**An asynchronous lifecycle API for running agent CLIs: start a turn, wait, cancel, time out, close, and keep a
recording of every run. Claude Code and Codex run on it today, as one-shot calls and as retained multi-turn sessions,
and each run can be recorded through Agent Journal. Also: no Spring AI, and a Spring-free core.**

Agent Client moves from 0.32.0 to 1.0.0 with this release. Versions do not follow semantic versioning: a deliberate
breaking change moves the major version, and older releases are not maintained. The breaking changes against 0.32.0
are listed below and were checked with japicmp.

## Headline: the lifecycle API

`AgentClient` gains an asynchronous route for providers that implement `AgentExecutionProvider`:

- `AgentClient.create(provider)`, `client.goal(..).runAsync()` and `client.sessions().create(..)` return an
  `AgentExecution` or a retained `AgentSession`;
- `AgentExecution` has `result(..)`, `cancel(..)`, `awaitTermination(..)` and `recording()`;
- closing a client or session confirms that its processes are gone within `cleanupTimeout`, and reports anything it
  could not confirm (`CleanupUnconfirmedException`, `CleanupSnapshot`) instead of hiding it;
- an `ExecutionRecorder` receives one decision per run, including failed, cancelled and timed-out runs, and its delivery
  is reported separately from the outcome (`RecordingSnapshot`).

The synchronous `AgentApi` / `AgentModel` route and every existing provider and starter keep working. `runAsync()` and
retained sessions refuse a legacy `AgentApi` explicitly. See [docs/async-lifecycle.md](docs/async-lifecycle.md).

New modules:

| Module | Published | Contents |
|--------|-----------|----------|
| `agent-client-claude` | yes | `ClaudeClientProvider`: Claude Code on the lifecycle route, one-shot and retained, with native transcript acquisition |
| `agent-client-codex` | yes | Codex on the lifecycle route, one-shot and retained (exact thread resume) |
| `agent-client-antigravity` | yes | Antigravity on the lifecycle route; it declares its native record unqualified |
| `agent-client-journal` | yes | `JournalExecutionRecorder`: writes each run's decision and native record through Agent Journal 2.0 |
| `agent-client-conformance` | no | Recording conformance rows: fixture providers through the shipped recorder |
| `agent-client-conformance-reader` | no | An independent reader JVM whose only dependency is Journal's |
| `agent-client-tutorials` | no | Compiles and runs the tutorials and how-tos under `docs/examples` |

## Breaking

- **Legacy Claude derived tracing (Journal 1.x) is removed.** `ClaudeAgentModel.Builder.traceDir`, `traceContentMode`
  and `archiveTranscript`; the Claude provider fields `phaseCapture`, `inputTokens`, `outputTokens`, `thinkingTokens`,
  `tracePath`, `transcriptPath` (blocking call and `ClaudeAgentSession.prompt`); the properties
  `agent-client.claude.trace-dir`, `trace-content-mode`, `archive-transcript`; and the `agent-claude` dependencies
  `journal-core` 1.10.0 and `claude-code-capture` 1.10.0 are gone. Use `capture(ClaudeCaptureOptions)` / the lifecycle
  acquisition surface and the `reportedUsage`/`totalCostUsd` fields instead. Details:
  [docs/legacy-tracing-removed.md](docs/legacy-tracing-removed.md).
- **No Spring AI dependency.** `AgentOptions`, `AgentResponse`, `AgentGeneration`, `AgentResponseMetadata` and
  `AgentGenerationMetadata` no longer implement Spring AI's `ModelOptions`, `ModelResponse`, `ModelResult`,
  `ResponseMetadata` and `ResultMetadata`; every method stays. No `org.springframework.ai` artifact is on any module's
  classpath, and the build fails if one returns. Code that typed these as Spring AI interfaces needs a source update.
  `agent-model` now declares `reactor-core`, formerly transitive through Spring AI. Every provider's `*AgentOptions`
  class is affected in the same way, because it implemented `ModelOptions` through `AgentOptions`.
- **Spring-free core; advisors use Client's own `Ordered`.** `agent-client-core` no longer depends on `spring-core`, and
  the build fails if any Spring artifact reaches its compile or runtime classpath. `AgentAdvisor` (and so
  `AgentCallAdvisor` and `AgentModelCallAdvisor`) now extends `io.github.markpollack.agents.client.Ordered` instead of
  Spring's `org.springframework.core.Ordered`. `getOrder()`, `HIGHEST_PRECEDENCE`, `LOWEST_PRECEDENCE` and
  `DEFAULT_AGENT_PRECEDENCE_ORDER` keep their values, so advisors that only override `getOrder()` compile unchanged.
  Code that passed an advisor where Spring's `Ordered` is expected, or sorted advisors with Spring's `OrderComparator`,
  needs a source update. A class that implements both `AgentAdvisor` and Spring's `Ordered` must qualify
  `HIGHEST_PRECEDENCE` and `LOWEST_PRECEDENCE`, which both interfaces now declare. The advisor chain sorts with a stable
  plain-Java comparator and keeps its `IllegalArgumentException` null checks.
- **Provider modules are framework-free; their Spring Boot code moved to `agent-client-spring-boot-autoconfigure`.**
  The nine legacy providers (`agent-amazon-q`, `-amp`, `-antigravity`, `-claude`, `-codex`, `-gemini`, `-grok`,
  `-junie`, `-qwen-code`) no longer depend on `spring-boot-autoconfigure` or carry auto-configuration. Their
  `*AgentAutoConfiguration` and `*AgentProperties` classes, `ClaudeHookAutoConfiguration`,
  `ClaudeAgentMcpProperties` and `ClaudePropertyOptionsMapper` keep their package and class names in
  `agent-client-spring-boot-autoconfigure`, so `exclude = ...` and property names are unchanged. The starters are
  unchanged and bring everything as before. An application that used a provider module without its starter and relied
  on its auto-configuration must add the starter (or `agent-client-spring-boot-autoconfigure`).
  `ClaudeHookBeanPostProcessor` moved from `io.github.markpollack.agents.claude.annotation` to
  `io.github.markpollack.agents.claude.autoconfigure`; the hook annotations stay in `agent-claude`. `agent-launcher`
  declares `logback-classic` and `jul-to-slf4j` directly instead of `spring-boot-starter-logging`, which only grouped
  them. The build now fails if any module other than the Spring Boot integration and the starters depends on Spring
  outside test scope.
- **MCP servers a provider cannot use are refused** (`90f37a9f`). On the ACP route (Grok, Junie) configured MCP servers
  were silently dropped. They are now sent in `session/new`: stdio servers always, HTTP and SSE servers when the agent
  advertised that transport. A server whose transport the agent did not advertise is refused with
  `UnsupportedOperationException` before any session opens; the blocking call reports it as an error response.
- **ACP 0.81.0; terminal no longer advertised** (`a17b04e9`). `agent-acp` moves from ACP Java SDK 0.17.0 to 0.81.0 and
  depends on `acp-json-jackson2`. The client no longer advertises the `terminal` capability, which it never served. The
  working directory is sent as an absolute path, and the run timeout also bounds `session/prompt`.
- **Gemini MCP settings no longer touch the workspace** (`b35143af`). MCP servers go in an owner-only system settings
  file in a private temporary directory, passed as `GEMINI_CLI_SYSTEM_SETTINGS_PATH`, instead of overwriting
  `<workspace>/.gemini/settings.json`. Client refuses when a system settings file already exists or the variable is
  set, and a sandbox that cannot see host files refuses MCP servers. `CLITransport.writeMcpSettings` and
  `cleanupMcpSettings` are removed.

## API check against 0.32.0 (japicmp)

japicmp 0.23.1 compared every module published as 0.32.0 (32 jars) with this release, with each version's full
classpath. It reports 32 changes that are both binary- and source-incompatible, in 14 modules. All of them are the
intended removals above:

- **Spring AI interfaces removed** (18 classes in 13 modules): `AgentOptions`, `AgentResponse`, `AgentGeneration`,
  `AgentResponseMetadata`, `AgentGenerationMetadata`, `DefaultAgentOptions`, the provider options classes
  (`AmazonQ`, `Amp`, `Antigravity`, `Claude`, `Codex`, `Gemini`, `Grok`, `Junie`, `QwenCode`, `Swe`) and
  `PortableAgentOptions` / `PortableMcpAgentOptions` in `agent-tck`.
- **Spring `Ordered` removed** from `AgentAdvisor`, `AgentCallAdvisor` and `AgentModelCallAdvisor`.
- **Legacy Claude tracing removed:** `ClaudeAgentModel.Builder.traceDir`, `traceContentMode` and `archiveTranscript`, and
  the matching getters and setters on `ClaudeAgentProperties`.
- **Gemini workspace MCP helpers removed:** `CLITransport.writeMcpSettings` and `cleanupMcpSettings`.

The other 18 published modules report no incompatible change. japicmp checks signatures only. The behaviour changes
above (refused MCP servers, ACP capabilities, Gemini settings) are listed by hand.

## Compatibility

Against 0.32.0:

- Public interfaces `AgentClient`, `AgentClient.AgentClientRequestSpec` and `AgentClient.Builder` gained methods with
  defaults, so implementations compiled against 0.32.0 keep working; the lifecycle-only options refuse explicitly.

Only for code written against the 0.32.1 development snapshots (the lifecycle route is new since 0.32.0):

- **The lifecycle provider modules have their own packages.** `agent-client-claude`, `agent-client-codex` and
  `agent-client-antigravity` moved from `io.github.markpollack.agents.claude`, `.codex` and `.antigravity`, which they
  shared with the legacy providers, to `io.github.markpollack.agents.client.claude`, `.client.codex` and
  `.client.antigravity`. For example, `ClaudeClientProvider` is now
  `io.github.markpollack.agents.client.claude.ClaudeClientProvider`; update the imports. The shared packages broke the
  Java module system and Quarkus dev and test mode, where the two jars can load in different class loaders. Six
  `agent-claude` classes that the Claude lifecycle module uses are now public and marked internal: `CaptureFiles`,
  `ClaudeCaptureSession`, `ClaudeCliOptionsMapper`, `ClaudeExecutionResult`, `ClaudeNativeArchive` and
  `ClaudeWireCapture`. They are not a supported API.
- Closing a Client or session no longer refuses a recording still to be delivered. Close drains it within the cleanup
  budget (`cleanupTimeout`, 10 s by default), so a caller that cancels or abandons a run and closes at once still gets its
  archive. A recording that outlasts the budget is reported by close as unconfirmed cleanup (`recorder-executor`) and
  finishes afterwards.
- `SessionSnapshot` gained a `diagnostics` component (the five-argument constructor is kept): a closed session reports the
  provider's recording diagnostics, such as the Codex thread-manifest outcome.
  Record patterns deconstructing `SessionSnapshot` with five components need a source update (six components now).
- `RecordingSnapshot` gained a `nativeRecording` component (the seven-argument constructor is kept), which changes its
  record deconstruction shape and `equals`. Record patterns with seven components need a source update (eight now).

## Added

- **Micronaut and Quarkus integrations.** `agent-client-micronaut` (Micronaut platform 4.10.20, core 4.10.30), and the
  Quarkus extension `agent-client-quarkus` with `agent-client-quarkus-deployment` (Quarkus 3.40.1). Each provides an
  `AgentClient.Builder` bean for the application's `AgentApi`, a new builder per injection point as in the Spring Boot
  integration. Each also provides the lifecycle providers whose module is on the classpath and that are enabled:
  `agent-client.lifecycle.<claude|codex|antigravity>.enabled=true`, with `working-directory`, `executable`,
  `acquisition-directory` with `cli-version`, and `sandbox` (Codex and Antigravity). In Quarkus, `enabled` is a
  build-time property. Both import the Jackson BOM ahead of their framework BOM, since both frameworks manage Jackson
  2.21.x, below 2.22.3.

- Codex retained sessions resume the exact emitted thread with a replacement process per turn; per-turn originals and a
  thread-level manifest (`journal-codex-retained-generation/1`).
- Claude fresh-start observation capture (`ClaudeClientProvider.Builder.freshTranscript`) producing
  `journal-retained-fresh-generation/1` with the ordered observation journal.

## Known qualification gaps

These gaps are documented rather than closed. They are tracked on the project roadmap.

- **Claude fresh-start capture is NOT fully qualified.** It ships under a promotion exception for development snapshots.
  One real two-turn Claude 2.1.316 run was observed; Journal's frozen text-turn profile could not qualify the native
  record shape, and acceptance rests on Journal's successor fresh profile with **exhaustive identity INCOMPLETE**: the
  observer's same-session scan of the native projects root is capped at directory depth 3 (deeper directories are counted
  as skipped, 168 in the observed run), so it does not prove the chosen session id is unique below that depth.
- **Recorded `freshStart.launchArgv` may be truncated.** The observed argv comes from `ProcessHandle.Info.arguments()`,
  which on JDK 21 stops at the first empty argument (for example `--tools ""`); flags after it are not recorded. The
  native `system init` and transcript records still carry `permissionMode`. The SDK-built argv is not yet recorded
  alongside the observed one.
- **Codex retained sessions are native-observed once:** one two-turn run on codex-cli 0.166.0 decided the resume
  identity, usage-scope and settings rules; the three-turn case and other CLI versions are fixture-qualified only.
- **No hosted live-CLI qualification.** CI runs no credentialed smoke or parity test automatically; those workflows are
  dispatch-only.
