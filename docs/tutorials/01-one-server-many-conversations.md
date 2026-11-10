# One server, many conversations

> **On main, not yet released.** This tutorial uses the asynchronous lifecycle API in Agent Client
> `0.32.1-SNAPSHOT`, built from `main` (at or after commit `4de205db`). It is not in 0.32.0. Resolve it from
> `https://central.sonatype.com/repository/maven-snapshots/` with snapshots enabled.

A server process creates one `AgentClient` at startup and closes it at shutdown. HTTP requests never build their own
client. Each conversation is a retained `AgentSession`. The application keeps the map from its own conversation id to
the session, so a later request can find the conversation and continue it.

```java
/** Create once at startup. The Client runs each execution on its own virtual thread. */
public ConversationServer(AgentExecutionProvider provider) {
    this.client = AgentClient.builder(provider).defaultTimeout(Duration.ofMinutes(5)).build();
}
```

The Client runs each execution on its own virtual thread, so there is no executor to configure. Start a conversation
in its workspace and remember its id:

```java
/** Starts a conversation in its own workspace and returns its id. */
public String start(Path workspace) {
    AgentSession conversation = this.client.sessions().create(spec -> spec.workingDirectory(workspace));
    this.conversations.put(conversation.id(), conversation);
    return conversation.id();
}
```

A later HTTP request names the conversation. Look it up and continue it with `runAsync()`; the turn joins the same
history. An id the server does not know is an error, never a silent new conversation:

```java
/**
 * A later HTTP request: find the conversation by id and continue it. An unknown id is an
 * error; it never silently becomes a new conversation.
 */
public AgentExecution send(String conversationId, String text) {
    AgentSession conversation = this.conversations.get(conversationId);
    if (conversation == null)
        throw new NoSuchElementException("No conversation " + conversationId);
    return conversation.goal(text).runAsync();
}
```

At shutdown, closing the Client closes every conversation it still owns:

```java
public void close() {
    this.client.close();
}
```

`runAsync()` returns at once with an `AgentExecution`. Call `result()` to wait for the answer, or hand the execution to
whatever streams the reply back. One conversation runs one turn at a time; for several users in one conversation, see
[the shared conversation tutorial](02-shared-conversation-arrival-order.md).

The full class is [ConversationServer.java](../examples/tutorials/ConversationServer.java). Its `main` runs it against
Claude Code with `ClaudeClientProvider`.

The build compiles this tutorial from `docs/examples/tutorials` and runs it against `OfflineProvider`, an offline
stand-in that makes no model call (`agent-client-tutorials`, `ConversationServerTest`). A live Claude Code run is not part of the build.
