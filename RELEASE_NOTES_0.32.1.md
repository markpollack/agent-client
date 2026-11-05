# Agent Client 0.32.1 (draft)

**Claude and Codex retained sessions through the lifecycle client, Claude fresh-start observation capture for Journal's
fresh profile, close-time recording diagnostics, and a dependency/runtime compatibility change for legacy Claude tracing.**

## Compatibility

- **Dependency/runtime compatibility change (not "no breaking change"):** `agent-claude` now declares Journal 1.x
  (`journal-core` 1.10.0, `claude-code-capture` 1.10.0) as *optional*. The legacy derived-trace surface (`traceDir`,
  `traceContentMode`, `phaseCapture`, `tracePath`, `transcriptPath`, properties `trace-dir`/`trace-content-mode`) keeps its
  0.32.0 behaviour when both artifacts are on the runtime class path and fails at build/startup with an actionable message
  naming them when they are not. `ClaudeAgentProperties.getTraceContentMode()` returns the bound name (`String`). Everything
  else runs without Journal 1.x. Migration: [docs/legacy-tracing-migration.md](docs/legacy-tracing-migration.md).
- Public interfaces `AgentClient`, `AgentClient.AgentClientRequestSpec` and `AgentClient.Builder` gained methods with
  defaults, so implementations compiled against 0.32.0 keep working; the lifecycle-only options refuse explicitly.
- `SessionSnapshot` gained a `diagnostics` component (the five-argument constructor is kept): a closed session reports the
  provider's recording diagnostics, such as the Codex thread-manifest outcome.

## Added

- Codex retained sessions resume the exact emitted thread with a replacement process per turn; per-turn originals and a
  thread-level manifest (`journal-codex-retained-generation/1`).
- Claude fresh-start observation capture (`ClaudeClientProvider.Builder.freshTranscript`) producing
  `journal-retained-fresh-generation/1` with the ordered observation journal.
- `ClaudeAgentModel.Builder.traceContentModeName(String)` for configuration binding without Journal types.
