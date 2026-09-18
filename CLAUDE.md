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
