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
- `AgentSessionRegistry.create(Path, String, McpServerDefinition)` opens a conversation
  with a fixed named connection. `AgentSession.prompt(String, Consumer<AgentSessionEvent>)`
  streams text, tool calls/results and a terminal outcome. Legacy providers explicitly reject
  these optional capabilities. The application supplies the selected registry.
- `ClaudeAgentSessionRegistry` reserves a UUID and opens the SDK connection without a paid
  startup prompt. `ClaudeAgentSession` retains that client across ordered, non-overlapping
  turns. `cancelActiveTurn()` closes only its SDK client, preserving SDK descendant cleanup;
  after the turn unwinds, `resume()` reconnects the same conversation. Explicit close is terminal.
- Claude sessions translate the portable definition with the existing model translator, merge
  unrelated native servers, and reject same-name collisions. They own a temporary JSON file and
  pass its path through SDK `CLIOptions.extraArgs["mcp-config"]` on initial launch and resume.
  This bypasses the SDK's silent file-write fallback. No strict MCP or settings-source flag is
  added, so ordinary configured tools remain available. SDK in-process MCP servers are explicitly
  unsupported by this connection-only session path; the one-shot model path is unchanged.
- Invalid connection URLs and configuration-file failures fail before launch. Claude's init
  message must confirm the scoped server is connected before assistant output is accepted.
  Remote connection, authentication and CLI capability failures can surface on the first real
  prompt. Opening alone is not evidence of authentication or tool usability.
- `AcpAgentModel.executePrompt` creates a new client/session, sends an empty
  `NewSessionRequest.mcpServers` list, prompts once, then closes. `AcpMergedOptions` preserves
  definitions that this path does not deliver. HTTP capability negotiation, retained lifetime,
  observer delivery and exposed session cancellation remain missing.
- `CodexAgentSessionRegistry.builder().approvedTools(Set.of("tool_name")).build()` opens
  conversations through the same scoped `create` contract. The registry reserves a stable semantic
  ID; the first real prompt learns the separate Codex thread ID. Later prompts use exact-thread
  `codex exec resume`, never `--last`. A cancelled or failed turn requires `resume()` first;
  cancellation before a thread ID arrives cannot be resumed. Explicit close is terminal.
- Codex sessions support HTTP definitions with an optional Bearer Authorization header. The URL,
  bearer environment-variable reference, required/enabled flags and per-tool `approval_mode`
  reach each invocation as repeated global `-c` arguments. The bearer value is passed only in the
  child environment. zt-exec environment logging is disabled; echoed secret values are redacted
  before observer delivery. No persistent provider configuration is written. Use a distinct scoped
  server name; inherited same-name configuration precedence has not been qualified.
- Codex uses never approval and a read-only sandbox for this session path. `approvedTools` names
  exact tools on the scoped server only; this is connection policy, not callback registration.
  Missing approvals, unsupported transports/headers and invalid URLs fail before launch. Required
  server startup failures, CLI rejection, invalid events and identity changes fail the turn.
  Opening alone proves neither endpoint authentication nor tool usability.
- Codex conversation JSON lines stream assistant messages and MCP tool calls/results, followed by
  one terminal outcome. Cancellation interrupts only the active session's wait and direct child.
  The new session path does not harvest journal rollouts; the existing one-shot model capture path
  remains available. Deterministic adapter and fake-child tests cover two turns, argv/environment,
  resume, rejection, observer errors, cancellation and cleanup. Live conformance is still required.
- `AntigravityClient.resume` and native `GrokClient.resume` exist beneath one-shot model adapters.
  These APIs alone do not prove retained MCP tools. Portable MCP translation is absent there.
- Claude model `interrupt()` closes all its tracked clients; SDK close enumerates descendants
  and terminates the child. Native Codex/Grok/Antigravity transports use zt-exec direct-child
  interruption/timeout cleanup, and their client close methods do not stop active execution.
  The ACP SDK supports `session/cancel`, but the model does not expose it; ACP close terminates
  its direct child. None of these operations cancels an application Java handler automatically.
- `ClaudeAgentSessionTest` deterministically verifies configuration at the SDK boundary and
  argv, retained turns, resume, event ordering, explicit failures, cancellation isolation and close.
  `ClaudeAgentMcpIT` is tagged `live` and excluded by the default test naming rules. Its two real
  prompts require HTTP discovery, exact tool requests, fresh original-JVM receipts, observed tool
  results, model use and first-turn recall. Passing deterministic tests is not live qualification.

Provider capture dependencies stay in provider modules. Existing upstream Reactor and MCP SDK
transitives are separate from application-owned tool-bridge dependency choices.

Conversation validation commands (Java 21):

```bash
./mvnw spring-javaformat:apply
./mvnw spring-javaformat:validate verify
# Opt-in only, invokes Claude twice; do not use the whole failsafe suite for this check.
./mvnw -pl agent-models/agent-claude -am -Pfailsafe \
  -Dit.test=ClaudeAgentMcpIT -Dfailsafe.failIfNoSpecifiedTests=false \
  -Dfailsafe.rerunFailingTestsCount=0 verify
```

For an isolated dependency cache, add `-Dmaven.repo.local=/absolute/path/to/cache` to each
command. Neither validation command requires `install`. Cancellation does not cancel
application-owned Java handlers; the application coordinates that separately.
