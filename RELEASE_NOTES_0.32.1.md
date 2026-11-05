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

## Known qualification gaps (development snapshot)

This snapshot is published with the following gaps documented rather than closed. They are tracked on the next
snapshot's roadmap.

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
