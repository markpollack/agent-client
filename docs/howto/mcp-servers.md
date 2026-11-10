# Give an agent MCP servers

Agent Client has a portable MCP layer. You define MCP servers once, the Client chooses which ones a request gets,
and each provider translates the chosen definitions into its own CLI's MCP configuration or refuses them. The
catalog, Client defaults and per-request names have shipped since well before 0.32.0. The rows marked
**On main, not yet released** need `0.32.1-SNAPSHOT`.

## Define the servers

Load a catalog from a file, or from a directory of `*.json` files, in the common `"mcpServers"` format. A server
without a `type` is stdio. `${NAME}` values expand from the environment:

```json
{"mcpServers": {
  "files": {"command": "npx", "args": ["-y", "@modelcontextprotocol/server-filesystem", "/workspace"]},
  "search": {"type": "http", "url": "https://mcp.example.com/search", "headers": {"Authorization": "Bearer ${SEARCH_TOKEN}"}}
}}
```

```java
/**
 * Load the catalog from a file (or a directory of {@code *.json} files) in the common
 * {@code "mcpServers"} format. {@code ${NAME}} values expand from the environment.
 */
public static McpServerCatalog fromFile(Path json) {
    return McpServerCatalog.fromJson(json);
}
```

Or build the same catalog in code. A server is a `StdioDefinition`, an `SseDefinition` or an `HttpDefinition`:

```java
/** Or build the same catalog in code. */
public static McpServerCatalog inCode() {
    return McpServerCatalog.builder()
        .add("files",
                new McpServerDefinition.StdioDefinition("npx",
                        List.of("-y", "@modelcontextprotocol/server-filesystem", "/workspace")))
        .add("search",
                new McpServerDefinition.HttpDefinition("https://mcp.example.com/search",
                        Map.of("Authorization", "Bearer token")))
        .build();
}
```

## Choose servers on the Client and per request

Register the catalog on the Client and name the default servers every request gets:

```java
/** Register the catalog once; every request gets the default servers. */
public static AgentClient client(AgentApi agent, McpServerCatalog catalog) {
    return AgentClient.builder(agent).mcpServerCatalog(catalog).defaultMcpServers("files").build();
}
```

A request adds servers by name. They join the defaults rather than replacing them:

```java
/** A request adds servers by name; they join the defaults. */
public static AgentClientResponse withSearch(AgentClient client) {
    return client.goal("Find the open issues about caching").mcpServers("search").run();
}
```

A name missing from the catalog fails with `IllegalArgumentException` before the provider is called. Naming
servers without registering a catalog fails with `IllegalStateException`.

## What each provider does

| Provider | Route | MCP servers |
|---|---|---|
| Claude Code (`agent-claude`) | `AgentModel` | Translated to Claude's MCP configuration: stdio, SSE and HTTP. A Claude-specific `mcpServers` option overrides a portable server of the same name. |
| Gemini CLI (`agent-gemini`) | `AgentModel` | **0.32.0 and earlier:** written to `<workspace>/.gemini/settings.json` and deleted after the run, which overwrote and then removed a workspace's own Gemini settings file. **On main, not yet released:** written to an owner-only session settings file outside the workspace, passed with `GEMINI_CLI_SYSTEM_SETTINGS_PATH` and allow-listed to Client's servers with `mcp.allowed`; the workspace is never touched. Refused before the CLI starts when Gemini system settings already exist at the platform path (`/etc/gemini-cli/settings.json` on Linux) or `GEMINI_CLI_SYSTEM_SETTINGS_PATH` is already set, so an administrator's settings are never bypassed, and when the sandbox is not local. |
| Grok, Junie (ACP, `agent-acp`) | `AgentModel` | **0.32.0:** not sent; the servers are dropped. **On main, not yet released:** stdio servers are always sent in the ACP `session/new`. HTTP and SSE servers are sent when the agent advertises that transport; otherwise the call fails before a session opens. |
| Amp (`agent-amp`) | `AgentModel` | **0.32.0:** not passed; the servers are dropped. **On main, not yet released:** passed with Amp's `--mcp-config` in an owner-only temporary file, deleted after the run, and merged with the user's Amp settings. Stdio, SSE and HTTP; Amp detects a remote server's transport from its URL. |
| Codex, Antigravity, SWE-agent, Amazon Q, Qwen Code | `AgentModel` | **0.32.0:** not passed; the servers are dropped. **On main, not yet released:** Client has no translation for these CLIs, so the call is refused before the CLI starts. Codex, Antigravity, SWE-agent and Qwen Code return an unsuccessful response naming the servers; Amazon Q throws `UnsupportedOperationException`. |
| Claude Code (`ClaudeClientProvider`) | Lifecycle, **on main, not yet released** | Translated, as for `agent-claude`. Also supports the scoped binding below. |
| Codex (`CodexClientProvider`), Antigravity (`AntigravityClientProvider`) | Lifecycle, **on main, not yet released** | Refused before launch with `UnsupportedOperationException`. |

## A scoped MCP binding for a retained session

**On main, not yet released.** A retained lifecycle session can freeze one MCP server slot and an environment when
it is created, with `SessionSpec.binding(new ConnectionBinding(name, definition, environment))`. A later request can
replace the slot's definition with `refreshBinding(replacement)`, under the same slot name and environment; the
provider reconnects under that request's execution. A replacement that changes the slot name or the environment is
rejected. Only the Claude Code lifecycle provider implements refresh today.

The full class is [McpServers.java](../examples/howto/McpServers.java). The build compiles it and runs it against a
recording stand-in that shows which definitions reach the provider (`agent-client-tutorials`, `McpServersTest`).
The ACP translation is covered by `AcpMcpServersTests`, which runs an offline ACP agent fixture.
