# Separate conversations, independently

> **On main, not yet released.** This tutorial uses the asynchronous lifecycle API in Agent Client
> `0.32.1-SNAPSHOT`, built from `main` (at or after commit `4de205db`). It is not in 0.32.0. Resolve it from
> `https://central.sonatype.com/repository/maven-snapshots/` with snapshots enabled.

Alice and Nancy share one conversation, and Bob and Doug share another. The two conversations run at the same time,
and cancelling a turn in one leaves the other running.

Separate histories do not isolate files: two conversations in one directory can overwrite each other's work. Give
each conversation its own workspace:

```java
/**
 * Separate histories do not isolate file writes: two conversations in one directory can
 * overwrite each other's files. Give each conversation its own workspace.
 */
public static Pair open(AgentClient client, Path workspaces) throws Exception {
    Path first = Files.createDirectories(workspaces.resolve("alice-and-nancy"));
    Path second = Files.createDirectories(workspaces.resolve("bob-and-doug"));
    return new Pair(client.sessions().create(spec -> spec.workingDirectory(first)),
            client.sessions().create(spec -> spec.workingDirectory(second)));
}
```

Start a turn in each. Both run at once, each on its own virtual thread, against its own provider process:

```java
var alice = pair.aliceAndNancy().goal("Review service A").runAsync();
var bob = pair.bobAndDoug().goal("Review service B").runAsync();
```

Cancel Alice and Nancy's turn. Only that turn stops:

```java
/**
 * Cancel one conversation's turn. Only that turn stops; the other conversation's turn is
 * not touched and its result arrives as usual.
 * @return the cancelled turn's termination
 */
public static ExecutionTermination cancel(AgentExecution turn) throws Exception {
    turn.cancel(true);
    return turn.awaitTermination(Duration.ofSeconds(10));
}
```

Bob and Doug's turn is not touched and its result arrives as usual. Alice and Nancy's conversation is still usable;
its next turn reconnects, as [picking a conversation up later](04-picking-up-later.md) describes.

The full class is [SeparateConversations.java](../examples/tutorials/SeparateConversations.java).

The build compiles this tutorial from `docs/examples/tutorials` and runs it against `OfflineProvider`, an offline
stand-in that makes no model call (`agent-client-tutorials`, `SeparateConversationsTest`). A live Claude Code run is not part of the build.
