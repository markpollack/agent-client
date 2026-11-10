# Picking a conversation up later

> **On main, not yet released.** This tutorial uses the asynchronous lifecycle API in Agent Client
> `0.32.1-SNAPSHOT`, built from `main` (at or after commit `4de205db`). It is not in 0.32.0. Resolve it from
> `https://central.sonatype.com/repository/maven-snapshots/` with snapshots enabled.

A conversation can be interrupted in two ways: its provider process dies, or a turn is cancelled. Within the same
JVM, neither ends the conversation. Across a restart, it depends on the provider, and today Claude Code cannot be
loaded.

## After a dead process or a cancellation: send the next turn

There is no resume call. Send the next turn. A retained session reconnects to its native history, keeps its id, and
reports the reconnect on the turn's termination:

```java
/**
 * After a dead process or a cancellation there is nothing to call first: send the next
 * turn. A retained session reconnects to its native history, keeps its id and reports the
 * reconnect on the turn's termination.
 */
public static ExecutionTermination nextTurn(AgentSession conversation, String text) throws Exception {
    AgentExecution turn = conversation.goal(text).runAsync();
    turn.result();
    return turn.awaitTermination(Duration.ofSeconds(10));
}
```

For Claude Code on main, the next turn starts a new CLI process that resumes the conversation's native session. Nothing
is replayed. If the CLI never reported the session's identity, the reconnect fails rather than starting an empty
conversation. A turn whose cleanup could not be confirmed quarantines the conversation: later submits are refused
`CLEANUP_UNKNOWN`, and the application closes it and, if it wants, starts a new conversation that it calls new.

## After a restart: load, where the provider supports it

Before shutdown, keep the provider's reference to the native history:

```java
/** Before shutdown, keep the provider's reference to the native history, once there is one. */
public static Optional<ProviderReference> reference(AgentSession conversation) {
    return conversation.providerReference();
}
```

After a restart, load it only if the provider says it can. Where loading is not supported, the load is refused with
the provider's reason; it never falls back to a fresh conversation or replays earlier prompts. (A provider that can
load would give the loaded conversation a fresh logical id with the prior history. No provider on main loads yet.)

```java
/**
 * After a restart: load the saved conversation only if the provider says it can.
 * Otherwise refuse, with the provider's reason. Never fall back to a fresh conversation
 * under the old id: if the application starts a new one, it says so.
 */
public static AgentSession load(AgentClient client, AgentExecutionProvider provider, ProviderReference saved,
        Path workspace) {
    Capability load = provider.capabilities().load();
    if (!load.supported())
        throw new UnsupportedOperationException(
                "Cannot load " + saved.nativeId() + " after a restart: " + load.reason().orElse("unsupported"));
    return client.sessions().load(saved, spec -> spec.workingDirectory(workspace));
}
```

**Claude Code: not supported yet.** No provider on main implements loading: `ClaudeClientProvider` reports `capabilities().load()` as unsupported
("Cross-handle/restart ownership exclusion is not qualified"), and `client.sessions().load(...)` throws
`UnsupportedOperationException`. After a restart, an application can start a new conversation and give it context
explicitly, but it is a new conversation, not the old one.

The full class is [PickUpLater.java](../examples/tutorials/PickUpLater.java).

The build compiles this tutorial from `docs/examples/tutorials` and runs it against `OfflineProvider`, an offline
stand-in that makes no model call (`agent-client-tutorials`, `PickUpLaterTest`). A live Claude Code run is not part of the build. `PickUpLaterTest` also checks the load capability the real `ClaudeClientProvider` declares.
