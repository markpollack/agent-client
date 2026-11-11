# Async result lifecycle

> **On main, not yet released.** The lifecycle and recording API described here is in the
> `0.32.1-SNAPSHOT` development snapshots built from `main`; it is not part of 0.32.0.

The initial lifecycle API is available to providers implementing `AgentExecutionProvider`.
The `agent-client-claude` module supplies `ClaudeClientProvider` for Claude Code; see
[the native caller](examples/NativeClaude.java). A legacy `AgentApi` still supports
its existing synchronous path; `runAsync()` and retained Client sessions reject it explicitly.

```java
AgentExecutionProvider provider = new AgentExecutionProvider() {
    public Connection open(AgentOptions options) {
        return new Connection() {
            public Turn prepare(String executionId) {
                return new Turn() {
                    private volatile boolean stopped;
                    public AgentResponse call(AgentTaskRequest request) {
                        if (stopped) throw new CancellationException();
                        return new AgentResponse(List.of(new AgentGeneration(request.goal(),
                            new AgentGenerationMetadata("SUCCESS", Map.of()))));
                    }
                    public void stop(boolean interrupt) { stopped = true; }
                };
            }
            public void close() { }
        };
    }
};
try (AgentClient client = AgentClient.create(provider);
     AgentSession session = client.sessions().create(spec -> {})) {
    AgentExecution execution = session.goal("first turn").runAsync();
    AgentClientResponse result = execution.result(Duration.ofSeconds(1));
    AgentExecution next = session.goal("follow up").runAsync();
    next.result();
}
```

Imports are from `io.github.markpollack.agents.client`, `io.github.markpollack.agents.model`,
`java.time`, `java.util` and `java.util.concurrent`. Use the Client `AgentSession`, not the legacy
model-layer interface. `AsyncLifecycleTest` exercises this production path with a deterministic
provider; the provider does not implement a separate result engine.

`result()` is interruptible. `result(Duration)` adds a caller-only timeout; neither interruption
nor caller timeout stops execution. An already available success, failure or cancellation is
observed without clearing the interrupt flag. Validate durations first; zero polls; negative durations
are rejected. Each failed observation throws a fresh `AgentExecutionException`, retaining the
original cause and a detached `FailureInfo`. Each cancellation observation throws a fresh
`CancellationException`. Response metadata is returned as a detached copy on each observation.

A positive `executionTimeout` starts at acceptance, includes required advisors and setup, and
excludes secondary recording. Deadline equality fails with `FailureCode.TIMEOUT`; it is not
cancellation. Explicit `cancel(false)` and `cancel(true)` both stop only the selected turn;
`true` also permits interrupting its owned worker. The provider's `Turn` must honor a stop that
arrives before dispatch and must never direct a stale stop to a successor.

Normal results follow required processing and safe session release. Failed/cancelled results may
precede cessation. `awaitTermination` observes that separate bounded fact. Missing cessation at
the original cleanup deadline freezes UNKNOWN and quarantines the session; later cessation cannot
make it reusable. A submit the session cannot admit dispatches nothing and throws
`AgentOperationException` with `failure()`: `BUSY` while an execution still holds the session (including a
cancelled or failed one whose work has not settled), `CLEANUP_UNKNOWN` once the session is quarantined,
`CLOSED` once it is closing. A caller's wait budget never restarts cleanup. `termination()` provides a protected
stage whose dependents run off the control/provider path; mutating a converted future cannot change
the authoritative observation. Implicit dependent callbacks are isolated even when registered after
completion; explicitly supplied executors retain the caller's scheduling policy.

Session admission and execution transitions share a private `ReentrantLock`. Each coherent immutable
execution revision is published through a volatile field, and condition waits recheck that value.
The publication edge for failure/cancellation does not cover work still running. Secondary observers
and recorders have distinct bounded lanes with no caller-runs fallback. The recorder seam delivers
Client decisions and requires a nonempty receipt ID returned by the sink. An outstanding attempt
becomes stale after 30 seconds; a late receipt retains that timeout history and leaves the execution
unchanged. An `ExecutionRecorder` prepares a frozen delivery job once. Explicitly classified transient
failures permit at most three serial attempts, with 100ms and 1s backoffs inside the original
30-second window. Close prevents further retries. A plain function remains a single-attempt sink.
The optional `agent-client-journal` module supplies `JournalExecutionRecorder` using the actual
Journal receiver, with one generic one-shot path for every provider. A provider turn seals its
native sources as a typed `NativeGeneration` (one member for a one-shot). The recorder re-checks the
manifest digest, selects Journal's `AcquisitionQualifier` by the acquisition profile, qualifies,
and delivers one initial binding under the qualifier's terminal profile. Claude and Codex one-shot
qualify at any CLI version on their routes: the observed version is recorded, not gated. It names the
acquisition profile (`claude-code-<version>-sdk-stdio-session-file`, `codex-cli-<version>-exec-stdio-rollout`),
and Journal delivers the qualified one-shot under `client-terminal-claude-sdk/1` or `client-terminal-codex-exec/1`.
Earlier recordings under versioned terminal profile names stay readable. Antigravity has no Journal qualifier and is recorded `UNQUALIFIED`. `recording()` reports
one of four outcomes in `nativeRecording()`: `ATTACHED`, `UNQUALIFIED`, `REJECTED` or `NOT_CAPTURED`.
Every outcome delivers one initial binding, so the call is archived and linked by its decision and
correlation; the last three are evidence-only, with a `missing` entry naming the profile or verdict.
No outcome changes the execution result. The outcome is set when the binding is prepared, before
delivery, so `ATTACHED` can sit beside a `Failed` delivery. A caller that needs native sources in the
archive checks both an `ATTACHED` outcome and a `Completed` delivery: `Completed` alone also covers the
three evidence-only outcomes. `JournalExecutionRecorder.claude(directory, namespace)`
is the same recorder. Retained turns are not one-shot generations and remain evidence-only here.
A native receiver ACK still needs independent complete-archive reader acceptance. Unclassified Journal I/O failures retain
acceptance uncertainty and do not retry automatically. An explicit retry of the same prepared job
can recover the original ACK without replaying provider work.

## Failed executions and closing

A failed `result()` throws `AgentExecutionException`; its cause chain and detached `failure()`
(`FailureInfo`: code, exception class, message, causes) carry the original failure. Recording settles
separately and never changes that failure. Once the recording lane has sealed the provider's native
capture, `execution.recording().evidence().fullRecord()` returns a `CaptureRecordReference` naming the
sealed manifest: its URI, acquisition profile and SHA-256. It is present also when the execution
failed and when Journal did not qualify the sources. The manifest holds what the provider observed;
for Codex that is argv, exit status, observed processes and the original stdin, stdout and stderr.
It is diagnostic evidence, not Journal qualification: `nativeRecording()` reports qualification.

The lane runs after the result, so close the client only after the recording has settled. A client
that closes first rejects the pending recording work and nothing is sealed. Wait only while delivery is
`Pending`, `Processing` or `Resubmitted`, and bound the wait: a client with no recording configured
reports `NotConfigured`, which never changes. From [FailedExecutions.java](examples/howto/FailedExecutions.java),
which the build compiles and runs offline:

```java
public static Outcome runThenClose(AgentClient client, String goal, Duration recordingBudget)
        throws InterruptedException, TimeoutException {
    AgentExecution execution = client.goal(goal).runAsync();
    Optional<FailureInfo> failure = Optional.empty();
    try {
        execution.result();
    }
    catch (AgentExecutionException failed) {
        failure = Optional.of(failed.failure()); // the original failure, unchanged by recording
    }
    execution.awaitTermination(Duration.ofSeconds(10));
    long deadline = System.nanoTime() + recordingBudget.toNanos();
    while (inFlight(execution.recording().delivery()) && System.nanoTime() < deadline) {
        Thread.sleep(10);
    }
    RecordingSnapshot recording = execution.recording();
    recording.evidence()
        .fullRecord()
        .ifPresent(record -> System.out.println(record.uri() + " " + record.sha256().orElse("")));
    client.close();
    return new Outcome(failure, recording.delivery(), recording.evidence().fullRecord());
}

static boolean inFlight(TerminalDelivery delivery) {
    return delivery instanceof TerminalDelivery.Pending || delivery instanceof TerminalDelivery.Processing
            || delivery instanceof TerminalDelivery.Resubmitted;
}
```

Claude retained sessions expose their native `providerReference()` after observing its identity.
After confirmed teardown they reconnect to that exact history without replaying a prompt. Loading
a reference into another handle is explicitly unsupported. `SessionSpec.binding` freezes an optional
MCP slot and environment; a request may `refreshBinding` with replacement definitions under the same
slot and environment. Replacement reconnects under the reserved execution. Progress events are
immutable, serialized and bounded (64 queued events); slow observers drop progress without blocking
execution. No public API freeze is implied.

Standalone session creation has a setup budget (default 30 seconds). Failed, timed-out or interrupted
acquisition throws `AgentOperationException` with its initiating cause and read-only `CleanupHandle`.
Late connections remain owned and are closed; no session is returned after concurrent Client close.
An interrupted creator restores its interrupt flag after bounded cleanup.

Session and Client `close()` wait within one original cleanup budget (default 10 seconds), including
older callback/recorder lanes. Client close starts all session teardowns before waiting. Unconfirmed
cleanup throws `CleanupUnconfirmedException`, retaining ownership and a read-only handle. Repeated
close does not restart the allowance. Self-close from an owned worker or callback refuses before
changing admission. Borrowed providers are not closed.

Provider metadata can implement `AgentMetadataValue` to supply a trusted immutable detached copy.
`ClaudeAcquisition` supports this boundary. Required advisor failure retains detached provider response
evidence in `responseSnapshot()` without turning it into an accepted successful execution.

See [the complete recorded caller](examples/RecordedAsync.java) and
[capabilities and migration limits](async-capabilities.md).

Tutorials, each compiled and run offline by the build: [one server, many conversations](tutorials/01-one-server-many-conversations.md),
[a shared conversation in arrival order](tutorials/02-shared-conversation-arrival-order.md),
[separate conversations](tutorials/03-separate-conversations.md) and
[picking a conversation up later](tutorials/04-picking-up-later.md).
