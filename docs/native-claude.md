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
Use a repaired SDK whose hook serializer omits absent controls and preserves nested permission fields.
For example, return `HookOutput.builder().hookSpecificOutput(
HookOutput.HookSpecificOutput.preToolUseDeny("Outside application policy")).build()`;
do not replace the decision with a continue-only response.
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

Callback invocation alone is not proof of native enforcement. Older SDK hook serialization emitted
null controls and flattened permission fields that the native CLI could reject while continuing tool
work. The repaired SDK JAR SHA256 is
`e995eeb0fb6c75797f1b06e76304b6e3a495ef5b4da37d11e92aa3f362b8e35a`
at `io.github.markpollack:claude-code-sdk:1.8.0-SNAPSHOT`; a SNAPSHOT coordinate alone does not select
these bytes. Independently verified one-shot readback uses Journal reader JAR SHA256
`6912a059a1f1758183854e9cbd5a3fa98ab05bf5d804d282a63875dd610de2e1` and writer
`b166baa65b3c0b82d9f3394631ae915d7a8ff172b126c8edcd4b0db43c1c1cf9`
at version `2.0.0-SNAPSHOT`. Archive verification and native permission enforcement remain separate.
The repaired SDK has native evidence of blocked Bash and out-of-workspace Read decisions on this
pinned route. Multiple denials in one final result currently expose a Journal normalization defect
with the listed reader/writer selection; independent full archive acceptance for that case remains
pending. Keep original sources and treat a failing reader result as a failure even after task success.

[ClientHookContract.java](examples/ClientHookContract.java) exercises the production public Client
path against the existing local subprocess fixture. Compile it strictly against installed runtime JARs
and run each case in a separate JVM with a fresh directory:

```text
ClientHookContract <allow|deny|throw|null|reject|eof|reject-success|cancel> <fresh-directory> <retained-child.py>
```

The fixture is at `agent-client-claude/src/test/resources/retained-child.py`. It validates the required
wire subset, records original received bytes and applies only task-local writes. The example asserts
one prompt/generation, observed cleanup and independent recording outcomes. It deliberately reports
incomplete acquisition because the fixture is not a native Claude archive. `reject-success` documents
the missing structured hook-rejection signal: a stderr diagnostic followed by native success is not
converted into a false claim that tool work was prevented. See [capabilities](async-capabilities.md).

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

### Retained diagnostic capture

`ClaudeClientProvider.Builder.acquisitionDirectory(...)` also enables a bounded original
transport buffer for each retained connection. A typed `ExecutionRecorder` receives the same
`ClaudeRetainedCapture` handle under `claudeRetainedCapture` for each turn in that generation.
Record the immutable decision immediately. After session close, call `capture.write()` off the
result path to export the original streams, hashes, observation windows and candidate prompt/result
locators. A turn result never seals the stream. The buffer is limited to 4 MiB, 16,384 byte
observations and 256 turn observations; overflow preserves a prefix and is explicitly incomplete.

The exported `client-retained-capture/1` document is a Client diagnostic format. It is **not a
Journal acquisition**: controls, read-ahead and ambiguous records remain unassigned, and a final
native-file snapshot does not establish per-turn membership or prefix high-water observations.
`transportComplete` describes observed stream closure/integrity only; `archiveQualified` is always
false. Missing natural EOF or dropped bytes cannot produce a complete transport claim. A failed
export retains its partial directory; it never overwrites or automatically retries another capture.

Current `JournalExecutionRecorder.claude(...)` persists retained decisions as decision-only packages.
The execution's recording snapshot can therefore show a successful delivery with capture still
pending; it does not promise a later automatic evidence revision. To attach retained evidence,
select the separate `retainedClaude(...)` recorder before either turn as described below. Its
initial envelope freezes the retained profile; an existing decision-only profile is not upgraded.
The diagnostic capture itself remains usable without that integration.

[RetainedClaudeCapture.java](examples/RetainedClaudeCapture.java) demonstrates two successful turns
on one child, delayed recording that does not block the second turn, close, and explicit capture
export using the local fixture. [ReadRetainedDecisions.java](examples/ReadRetainedDecisions.java)
runs separately with only the Journal reader and verifies the two original decision-only packages.
These are deterministic examples; native retained archive qualification remains pending.

### One-shot working directory

For the lifecycle Client path, an explicit request `workingDirectory(...)` takes precedence over
`Goal`'s directory and the Client builder default. The selected directory is copied into immutable
one-shot options before the provider process opens, so relative child operations use that directory.
Without any Client directory selection, existing provider/options defaults still apply.
A retained session keeps the home selected by `sessions().create(s -> s.workingDirectory(...))`;
a follow-up request directory supplies application context and does not relocate that process.
[ClientDirectoryPrecedence.java](examples/ClientDirectoryPrecedence.java) exercises request, goal,
builder and provider fallback, plus retained-home preservation, using a real local child marker.

### Retained two-turn archives

For the bounded, opt-in two-successful-text-turn profile, use
`JournalExecutionRecorder.retainedClaude(directory, namespace)`. Configure the provider with
`acquisitionDirectory(directory, "2.1.316")` and `retainedTranscript(exactNativeFile)`.
The latter must name an actually observable transcript before the first dispatch: absence is
unknown, not an empty prefix. Client reads bounded prefixes before and after each turn;
missing observations or unsupported source history cannot earn archive completeness.
This deterministic profile has not been qualified against a live retained CLI.

Each execution's normal recording freezes its original decision and pending association.
Conversation progress does not wait for secondary delivery. After closing the session and
observing both initial recording ACKs, retrieve `ClaudeRetainedCapture` from the recorder's
`claudeRetainedCapture` evidence entry and call `writeGeneration(originalDecisions)` once.
Pass that same manifest to `recorder.attach(executionId, manifest)` for each execution.
Journal qualifies and attaches the full originals plus per-turn projections to the original
binding. `recorder.attachment(executionId)` exposes the separate final delivery status;
`AgentExecution.recording()` continues to describe initial delivery.

Use a fresh retained recorder per two-turn session. Final attachment is explicit and synchronous
on the caller's thread, outside result/admission handling; there is no automatic final-delivery
scheduler. An explicit retry after an ambiguous receiver reply reuses the frozen association.
Initial and final ACKs carry no cross-execution ordering or latency guarantee. Inspect the
complete archive after all turns and close. The installed
[`RetainedClaudeArchive.java`](examples/RetainedClaudeArchive.java) example exercises the
workflow with a deterministic child fixture and a latch that blocks initial delivery while
both turns complete. Live retained qualification and other providers remain separate work.
