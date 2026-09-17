# Agent Client 0.31.0

**A cancel now stops a Claude call that is already running.** Built on Claude Agent SDK 1.7.0, which
destroys the CLI process tree before closing its streams.

## Why this release exists

`ClaudeAgentModel` opened its sync client in local scope on every execution path, so no handle escaped and
nothing could stop a call in flight: a blocked `receiveResponse()` had no way out, and the Claude CLI's child
processes went on editing the project after the request was abandoned.

Stopping the call is only half the fix. With the SDK's old close order, ending the blocked call still left the
child process alive — a live cancel returned in 0.1 s while the CLI survived a 45-second settle and the
following turn. Claude Agent SDK 1.7.0 destroys the process tree first; this release is built and tested
against it.

## What changed

- **`ClaudeAgentModel.interrupt()`** (`aedea91`). The model tracks its in-flight clients at the three execution
  paths — `call()`, `iterate()` and streaming — and `interrupt()` closes each one so the blocked call ends.
  `close()` delegates to it, so a consumer that already calls `close()` on cancel gets this with no change.
  The availability probe is deliberately not tracked: it closes inside its own method, and tracking it would
  only let a cancel turn a health check into a spurious failure.
- **claude-code-sdk 1.5.1 → 1.7.0** (`3b183a7`, pinned to the release in `87b81c4`).

`AgentModel` is unchanged — no new interface method. `call()` still catches and returns an error response
rather than throwing, so a cancelled `call()` is not identifiable from its return value alone; `stream()`
surfaces the exception as before.

## Upgrading

Drop-in. Bump the `agent-client` coordinates to 0.31.0; take `claude-code-sdk` 1.7.0 with it (the AgentWorks
BOM 1.21.0 manages both).

## Maven Central

`io.github.markpollack` version `0.31.0` for all 34 published modules (`agent-client-parent`,
`agent-client-core`, `agent-model`, `agent-claude`, the provider SDKs, agent models and starters,
`agent-launcher`, `agent-tck`). Each carries its consumer-rooted CycloneDX SBOM (`-cyclonedx.json`) as a signed
attachment.
