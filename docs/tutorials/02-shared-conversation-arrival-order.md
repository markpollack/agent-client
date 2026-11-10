# A shared conversation, in arrival order

> **On main, not yet released.** This tutorial uses the asynchronous lifecycle API in Agent Client
> `0.32.1-SNAPSHOT`, built from `main` (at or after commit `4de205db`). It is not in 0.32.0. Resolve it from
> `https://central.sonatype.com/repository/maven-snapshots/` with snapshots enabled.

Bob and Doug work in one conversation. Their HTTP requests can arrive at any moment, including while the agent is
still answering the other one. Agent Client runs one turn at a time per conversation and never queues a second prompt
behind a running one. The application decides what waits and in what order.

This tutorial's inbox is **application code, not a library API**: a bounded first-in-first-out queue per conversation,
with one worker that sends each input in arrival order.

```java
/** An HTTP request for this conversation: queue it behind the inputs already waiting. */
public Admission submit(Input input) {
    return this.inbox.offer(input) ? Admission.QUEUED : Admission.FULL;
}
```

Each input names its user, so the shared history records who asked what:

```java
/** Each input names its user, so the shared history says who asked what. */
static String attributed(Input input) {
    return "From " + input.user() + ": " + input.text();
}
```

The worker takes the next input, runs it, and waits for the turn to **terminate** before taking another:

```java
private void drain() {
    try {
        while (true) {
            Input next = this.inbox.take();
            AgentExecution execution = this.conversation.goal(attributed(next)).runAsync();
            this.current = execution;
            this.started.accept(next, execution);
            // Advance on termination, not on the result: a cancelled turn's result is
            // done at once, but its process may still be stopping and the session
            // would refuse the next input as BUSY.
            ExecutionTermination ended = execution.termination().toCompletableFuture().get();
            if (!ended.cleanupConfirmed())
                return; // the session is quarantined; nothing more can run in it
        }
    }
    catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
    }
    catch (ExecutionException ex) {
        throw new IllegalStateException(ex.getCause());
    }
}
```

Three things to see:

- **An input that arrives mid-turn waits its turn.** Doug's request arrives while Bob's turn runs. It is queued, and it
  is sent only after Bob's turn has ended.
- **The queue advances on `termination()`, not on the result.** Cancel Bob's turn and its result is done at once
  (`isDone()` is true), but its process may still be stopping. A next input sent at that moment would be refused.
  `termination()` completes when the turn has really ended.
- **An overlapping submit is refused `BUSY`.** Without the inbox, a second submit while a turn runs throws
  `AgentOperationException` with `failure()` `BUSY`, and nothing is sent. An endpoint that submits directly maps the
  refusal to an HTTP status:

```java
/**
 * Without an inbox, an HTTP endpoint submits directly and maps the refusal. Agent Client
 * never queues a second prompt behind a running one.
 * @return the HTTP status for this attempt
 */
public static int submitDirectly(AgentSession conversation, Input input) {
    try {
        conversation.goal(attributed(input)).runAsync();
        return 202;
    }
    catch (AgentOperationException refused) {
        return switch (refused.failure()) {
            case BUSY -> 409; // a turn is running: Conflict, or queue it as above
            case CLEANUP_UNKNOWN -> 503; // quarantined: start a new conversation
            case CLOSED -> 410; // the conversation has ended
            default -> 500;
        };
    }
}
```

The other refusals are `CLEANUP_UNKNOWN`, when a turn's cleanup could not be confirmed and the conversation is
quarantined, and `CLOSED`, when the conversation has ended. A full inbox answers `FULL` (HTTP 429), so a burst of
inputs cannot grow the queue without bound.

The full class is [ConversationInbox.java](../examples/tutorials/ConversationInbox.java).

The build compiles this tutorial from `docs/examples/tutorials` and runs it against `OfflineProvider`, an offline
stand-in that makes no model call (`agent-client-tutorials`, `ConversationInboxTest`). A live Claude Code run is not part of the build.
