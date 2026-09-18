# Agent Client Claude Code Bridge

Read and follow `AGENTS.md`. It is the canonical repository instruction file for all coding agents.
Do not duplicate project guidance or private steward state here.

Use the `AgentClient` facade when trajectory evidence is required:
`AgentClientResponse.getPhaseCapture()` exposes it, while `AgentApi.call()` does not. Capture is
not gated by `traceDir`; that setting controls only raw trace-file output.

A conversational application extends an installed agentic CLI: the CLI owns dialogue and context,
and Agent Client owns the semantic conversation and provider connection mechanics. A conversation
must support ordered prompts, resume where applicable, active-turn cancellation and close, with a
fixed session-scoped MCP definition and observable messages, tool activity and terminal outcomes.
Capability failures must be explicit, and behavioral tests must prove continuity and tool use.

The application owns provider selection, local Java tools and callbacks, the embedded MCP endpoint,
and domain workflows. Agent Client accepts ordinary connection definitions without knowing the
handlers behind them. It gains no `LocalTool`, callback registration, bridge, or Spring knowledge.
These are architectural requirements; provider support still requires behavioral qualification.

The Maven wrapper uses Java 21. Run `./mvnw verify` for the normal reactor suite;
`./mvnw clean verify` also removes prior build output. Live integration tests require the opt-in
`failsafe` profile, for example `./mvnw -Pfailsafe verify`, and provider prerequisites. Keep live
provider qualification separate from the normal test result.

Repository map:

- `agent-models/agent-model`: `AgentApi`, options, responses, optional `AgentSession` and
  `AgentSessionRegistry`, and portable MCP definitions/catalog.
- `agent-client-core`: facade, advisors, default/request MCP-name resolution and response accessors.
- `agent-models/agent-acp`: shared downstream ACP lifecycle and update folding; Junie delegates to
  it, and Grok can select it through `agent-client.grok.transport=ACP` (native CLI is the default).
- Other `agent-models/agent-*`: provider translation, options and provider auto-configuration.
- `provider-sdks/*`: native CLI clients and command construction. Claude uses external
  `claude-code-sdk`; ACP providers use external `acp-core`.
- `agent-starters`, `agent-client-spring-boot-autoconfigure`, `agent-launcher`, and `agents`:
  optional application integration and examples. `agent-models/agent-tck` holds contract support.

Verified integration seams and current limits:

- `DefaultAgentClient.resolveMcpServers` unions builder default names with request names and
  resolves them through `McpServerCatalog`. Selection is additive; the resolved map replaces the
  options definition map. `McpServerDefinition.HttpDefinition` carries URL and headers.
- `ClaudeAgentModel.buildCLIOptions` translates portable definitions to SDK configuration;
  Claude-native entries override matching names. `ClaudeAgentSessionRegistry` creates the only
  current `AgentSession` implementation. Its creation API lacks portable per-session options;
  `ClaudeAgentSession.resume` rebuilds options with the resume ID alone, losing MCP settings.
- `AgentSession` already supplies identity, directory, prompt, resume and close, but has no
  active-turn cancel or per-turn observer. It does not yet satisfy the complete conversation
  requirements. Extend this optional surface before introducing a duplicate abstraction.
- `AcpAgentModel.executePrompt` creates a new client/session, sends an empty
  `NewSessionRequest.mcpServers` list, prompts once, then closes. `AcpMergedOptions` preserves
  definitions that this path does not deliver. HTTP capability negotiation, retained lifetime,
  observer delivery and exposed session cancellation remain missing.
- `CodexClient.resume` and `AntigravityClient.resume` exist beneath one-shot model adapters.
  Native `GrokClient` also supports resume. These APIs alone do not prove retained MCP tools.
  Portable MCP translation is absent from these native adapters.
- Claude model `interrupt()` closes all its tracked clients; SDK close enumerates descendants
  and terminates the child. Native Codex/Grok/Antigravity transports use zt-exec direct-child
  interruption/timeout cleanup, and their client close methods do not stop active execution.
  The ACP SDK supports `session/cancel`, but the model does not expose it; ACP close terminates
  its direct child. None of these operations cancels an application Java handler automatically.
- `ClaudeAgentMcpIT` configures a dummy `echo` server and asserts nonblank response text. It does
  not assert tool discovery, invocation or result use. Reuse the deterministic translation,
  catalog, command and interrupt tests, then add behavioral conversation qualification.

Provider capture dependencies stay in provider modules. Existing upstream Reactor and MCP SDK
transitives are separate from application-owned tool-bridge dependency choices.
