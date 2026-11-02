# Async result lifecycle

The initial lifecycle API is available to providers implementing `AgentExecutionProvider`.
Existing CLI adapters do not yet implement that opt-in contract. A legacy `AgentApi` still supports
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
make it reusable. A caller's wait budget never restarts cleanup. `termination()` provides a protected
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
Journal receiver. Its profile records accepted decisions with unavailable native evidence; it does
not establish native capture or archive completeness. Unclassified Journal I/O failures retain
acceptance uncertainty and do not retry automatically. An explicit retry of the same prepared job
can recover the original ACK without replaying provider work.

Native provider rollout, provider-reference loading, binding refresh, native event/capture integration,
are subsequent increments. The initial `SessionSpec` supports fixed options and
working directory. No public API freeze or native qualification is implied.

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
