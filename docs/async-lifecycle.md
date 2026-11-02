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
observed without clearing the interrupt flag. Validate durations first; zero polls and negatives
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
the authoritative observation.

Session admission and execution transitions share a private `ReentrantLock`. Each coherent immutable
execution revision is published through a volatile field, and condition waits recheck that value.
The publication edge for failure/cancellation does not cover work still running. Secondary observers
and recorders have distinct bounded lanes with no caller-runs fallback. The recorder seam delivers
Client decisions only; it does not establish a Journal receipt, native capture or archive completeness.

Native provider rollout, provider-reference loading, binding refresh, native event/capture integration,
and Journal delivery are subsequent increments. The initial `SessionSpec` supports fixed options and
working directory. No public API freeze or native qualification is implied.
