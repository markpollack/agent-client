# BREAKING: legacy Claude derived tracing (Journal 1.x) removed in 0.32.1

Owner decision, 2026-11-04: "no Journal 1.x anywhere". The pre-acquisition Claude tracing surface is removed outright.
This is an accepted **breaking change**, not a deprecation.

## Removed

| Removed | Was |
|---|---|
| `ClaudeAgentModel.Builder.traceDir(Path)`, `traceContentMode(TraceContentMode)`, `archiveTranscript(boolean)` | derived JSONL trace per call, `transcripts/` archive, content policy |
| provider fields `phaseCapture`, `inputTokens`, `outputTokens`, `thinkingTokens`, `tracePath`, `transcriptPath` on Claude responses (blocking call and `ClaudeAgentSession.prompt`) | Journal 1.x `PhaseCapture` and derived paths |
| properties `agent-client.claude.trace-dir`, `trace-content-mode`, `archive-transcript` and their binding | Boot configuration of the above |
| `agent-claude` dependencies `io.github.markpollack:journal-core:1.10.0` and `io.github.markpollack:claude-code-capture:1.10.0` | transitive Journal 1.x on every Claude consumer's class path |

`AgentClientResponse.getPhaseCapture()` stays in core because other providers still publish a `phaseCapture`; for Claude it
returns null.

## Use instead

- **Lossless originals and native session files:** `ClaudeAgentModel.Builder.capture(ClaudeCaptureOptions)` for a blocking
  call; `ClaudeClientProvider.Builder.acquisitionDirectory(...)` (+ `retainedTranscript`/`freshTranscript`) for lifecycle
  sessions. These produce the Journal 2 acquisition packages that Journal qualifies.
- **Token and cost facts:** `reportedUsage`, `totalCostUsd`, `numTurns`, `terminalReceived` provider fields, taken from the
  CLI's result message without derivation.
- **Claude text and usage per turn with a Journal record:** `JournalExecutionRecorder` (agent-client-journal, Journal 2).

## Still carrying Journal 1.x (not changed here)

`agent-grok`, `agent-antigravity`, `agent-codex` (the model module) and `agent-junie` depend on their Journal 1.x
`*-cli-capture` 1.10.0 artifacts for their own phase parsing; removing those is a separate increment per provider.
