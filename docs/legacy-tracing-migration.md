# Legacy Claude tracing (Journal 1.x) is now an optional dependency

**This is a dependency/runtime compatibility change, not "no breaking change".** Signatures are unchanged and binary
compatible, but callers of the legacy derived-trace surface must now declare two dependencies that `agent-claude`
used to bring transitively.

## What changed in 0.32.1

`agent-claude` declares `io.github.markpollack:journal-core:1.10.0` and `io.github.markpollack:claude-code-capture:1.10.0`
as **optional**. Nothing in `agent-claude`, `agent-client-claude`, the Spring Boot auto-configuration or any ordinary
`AgentClient` path requires them: the module loads, Boot starts and blocking calls run without them. The legacy
surface below is active only when both artifacts are on the runtime class path.

| Legacy surface | With the two dependencies | Without them |
|---|---|---|
| `ClaudeAgentModel.Builder.traceDir(Path)`, `traceContentMode(TraceContentMode)`, `traceContentModeName(String)`; properties `agent-client.claude.trace-dir`, `trace-content-mode` | behaviour of 0.32.0: derived JSONL trace `agent-run-*.jsonl`, `transcripts/` archive, `tracePath`/`transcriptPath` fields | `build()` (and Boot startup) fails with: `… requires the legacy Journal 1.x derived-trace dependencies on the runtime classpath; add io.github.markpollack:journal-core:1.10.0 and io.github.markpollack:claude-code-capture:1.10.0 …` |
| `phaseCapture`, `inputTokens`, `outputTokens`, `thinkingTokens` provider fields; `AgentClientResponse.getPhaseCapture()` | populated as in 0.32.0 (blocking call and `ClaudeAgentSession.prompt`) | absent (`getPhaseCapture()` returns null); `terminalReceived`, `numTurns`, `reportedUsage`, `totalCostUsd` remain |
| `ClaudeAgentProperties.getTraceContentMode()` | returns the bound **name** (`String`), no longer the enum | same |

## Migration for legacy callers

Add to your POM (same versions `agent-claude` used):

```xml
<dependency><groupId>io.github.markpollack</groupId><artifactId>journal-core</artifactId><version>1.10.0</version></dependency>
<dependency><groupId>io.github.markpollack</groupId><artifactId>claude-code-capture</artifactId><version>1.10.0</version></dependency>
```

Java callers that read `ClaudeAgentProperties.getTraceContentMode()` as an enum convert the name with
`TraceContentMode.valueOf(name.toUpperCase())`. YAML/properties binding is unchanged (`trace-content-mode: FULL`).

## Why

Compositions that embed Journal 2 (for example the Actualize ACP server) proved a runtime with no Journal 1.x
fallback; restoring the legacy surface as a compile dependency would have put journal-core 1.10.0 back on every
consumer's runtime class path. Optional scope keeps that property while the documented two-line migration restores
the full 0.32.0 legacy behaviour for callers who use it. The lossless acquisition surface
(`capture(ClaudeCaptureOptions)`, retained captures) never depended on Journal 1.x.
