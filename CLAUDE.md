# Agent Client Claude Code Bridge

Read and follow `AGENTS.md`. It is the canonical repository instruction file for all coding agents.
Do not duplicate project guidance or private steward state here.

Use the `AgentClient` facade when trajectory evidence is required:
`AgentClientResponse.getPhaseCapture()` exposes it, while `AgentApi.call()` does not. Capture is
not gated by `traceDir`; that setting controls only raw trace-file output.
