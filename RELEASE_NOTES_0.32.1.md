# Agent Client 0.32.1 (draft)

**Claude and Codex retained sessions through the lifecycle client, Claude fresh-start observation capture for Journal's
fresh profile, close-time recording diagnostics, and the removal of the legacy Claude derived-tracing surface.**

## Breaking

- **Legacy Claude derived tracing (Journal 1.x) is removed.** `ClaudeAgentModel.Builder.traceDir`, `traceContentMode`
  and `archiveTranscript`; the Claude provider fields `phaseCapture`, `inputTokens`, `outputTokens`, `thinkingTokens`,
  `tracePath`, `transcriptPath` (blocking call and `ClaudeAgentSession.prompt`); the properties
  `agent-client.claude.trace-dir`, `trace-content-mode`, `archive-transcript`; and the `agent-claude` dependencies
  `journal-core` 1.10.0 and `claude-code-capture` 1.10.0 are gone. Use `capture(ClaudeCaptureOptions)` / the lifecycle
  acquisition surface and the `reportedUsage`/`totalCostUsd` fields instead. Details:
  [docs/legacy-tracing-removed.md](docs/legacy-tracing-removed.md). japicmp against main reports these removals; they are
  intended.

## Compatibility

- Public interfaces `AgentClient`, `AgentClient.AgentClientRequestSpec` and `AgentClient.Builder` gained methods with
  defaults, so implementations compiled against 0.32.0 keep working; the lifecycle-only options refuse explicitly.
- `SessionSnapshot` gained a `diagnostics` component (the five-argument constructor is kept): a closed session reports the
  provider's recording diagnostics, such as the Codex thread-manifest outcome.
  Record patterns deconstructing `SessionSnapshot` with five components need a source update (six components now).

## Added

- Codex retained sessions resume the exact emitted thread with a replacement process per turn; per-turn originals and a
  thread-level manifest (`journal-codex-retained-generation/1`).
- Claude fresh-start observation capture (`ClaudeClientProvider.Builder.freshTranscript`) producing
  `journal-retained-fresh-generation/1` with the ordered observation journal.
