# Claude through AgentClient

Add `io.github.markpollack:agent-client-claude` at your selected Client version. Use an installed,
authenticated Claude Code executable and Java21.

```java
var provider = ClaudeClientProvider.builder()
    .workingDirectory(Path.of("my-project"))
    .maxBudgetUsd(1.0)
    .build();
try (var client = AgentClient.builder(provider)
        .defaultTimeout(Duration.ofMinutes(2)).build()) {
    var execution = client.goal("Explain this project's main entry point").runAsync();
    System.out.println(execution.result().getResult());
}
```

Imports: `io.github.markpollack.agents.client.AgentClient`,
`io.github.markpollack.agents.claude.ClaudeClientProvider`, `java.nio.file.Path`, `java.time.Duration`.
`result(Duration)` bounds only the caller's wait. Set `executionTimeout` on a request to bound the
actual task. The native spending cap is fixed per connection generation. Supply `preToolUse` hooks
when the application needs to decide edit/tool permissions before execution.
One-shot executions use one connection generation; a disconnected generation fails without a
replacement launch. Retained sessions explicitly support reconnect as described below.

For a conversation, create `client.sessions().create(spec -> spec.workingDirectory(directory))`
and call `session.goal(...).runAsync()` sequentially. Close the session when done. `cancel(false)`
stops the selected execution; `awaitTermination` reports whether observed resources stopped.
A subsequent turn may reconnect to the same observed native history. Client does not silently replay
provider work. Unknown termination prevents reuse. Loading a provider reference into another handle
is not supported by this adapter.

Recording is optional. Add `agent-client-journal`, set
`providerBuilder.acquisitionDirectory(captures, observedCliVersion)`, and configure
`AgentClient.Builder.executionRecorder(JournalExecutionRecorder.claude(records, namespace))`.
Observe `execution.recording()` separately from `execution.result()`: recording can fail after a
successful task. The current native profile admits one closed text-message execution on Claude
Code2.1.316. Retained turns record decisions without claiming native archive completeness.

[NativeClaude.java](examples/NativeClaude.java) is an installed-JAR acceptance caller with a native
budget cap, exact pre-tool policy, correlation, one-shot capture, retained follow-up, cancellation
and reconnect. Compile it against the selected runtime JARs, then run `NativeClaude one-shot
<workspace> <output>` with `sentinel.txt` containing `CLIENT_NATIVE_SENTINEL`; use a fresh directory
for each invocation. Its `retained` mode performs four tasks and creates `cancel-started` in the
workspace. The distinct `archive-control` mode submits one text-only task with the hook still
configured. [ReadNativeClient.java](examples/ReadNativeClient.java) independently checks the native
archive using only Journal's reader JAR and runtime dependencies:

```text
ReadNativeClient <events.jsonl> <decisionId> <protected-sources> <assembly-expectation.json> [frozen-decision.json]
```

The protected sources live beside the receiver's prepared `events.jsonl`; the assembly expectation
is under `records/native-<executionId>`. See [capabilities and limits](async-capabilities.md) for
cleanup scope, source limits and deferred provider work.

For deterministic readback after a reader repair, [ReplayNativeArchive.java](examples/ReplayNativeArchive.java)
reassembles the exact frozen envelope and source package with the installed Journal writer:
`ReplayNativeArchive <envelope.json> <protected-sources> <fresh-destination> <producer-namespace>`.
Run the independent reader on the resulting archive, optionally supplying the original decision bytes
for an exact comparison. This performs no provider task and does not repair malformed original bytes.
