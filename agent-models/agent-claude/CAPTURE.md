# Blocking Claude acquisition

`ClaudeAgentModel` extracts the execution response from SDK messages. Detailed interpretation
and canonical storage belong to Journal. The Claude provider no longer depends on legacy
`journal-core` or `claude-code-capture`, and no longer returns `PhaseCapture` metadata.

```java
var capture = new ClaudeCaptureOptions(newDirectory, invocationId, observedCliVersion);
try (var model = ClaudeAgentModel.builder()
        .capture(capture)
        .workingDirectory(workspace)
        .timeout(Duration.ofMinutes(30))
        .defaultOptions(ClaudeAgentOptions.builder().model(modelName).yolo(true).build())
        .build()) {
    var response = AgentClient.create(model).goal(prompt).workingDirectory(workspace).run();
    ClaudeAcquisition acquired = ClaudeAcquisition.from(response.getAgentResponse());
    // Check execution status and acquired.complete() independently.
    // Hand acquired.manifest() to Journal; persist its canonical reference separately.
}
```

Capture is opt-in and applies to blocking `call` only. The directory must be new; one configured
destination represents one invocation and cannot be reused. The reference contains the manifest
path, its SHA-256, acquisition completeness and invocation/session identities. Source files may
contain sensitive prompts, credentials emitted by tools, and file contents: use private storage.
The full options constructor accepts a native projects directory and immutable build provenance.
Supply actual versions; setting a version does not qualify that version's Journal route.

Original stdin/stdout/stderr bytes are separate files. `observations.jsonl` records offsets and
host observation times, not protocol frames or a causal order across streams. Successful stdin
writes do not prove consumption. File failures are latched; flushing/closing is checked before
sealing. Failed executions retain an incomplete source package where sealing remains possible.
Failure to seal throws and names the retained source directory. The caller should not mutate a
sealed package; hashes allow the consumer to reject changes.

Native lookup requires the exact observed session ID and matching native content. No newest-file
fallback is used. Relevant files beneath that session directory retain their owner and origin;
missing sources and unknown process exit code stay explicit. The SDK does not expose a process
exit receipt; a received terminal result is not proof of exit zero or domain success. Native
availability and canonical adequacy require separate consumer checks.

Ordinary response metadata retains available reported cost, usage, turn count and terminal
presence. Missing reported cost is absent, not zero; no per-tool allocation is made. Retained
sessions use the same terminal extraction but do not gain capture parity with this blocking path.
`traceDir` is removed in 0.32.1 (legacy derived tracing, breaking); use
explicit capture settings. The derived trace-content modes and their properties are removed; Spring Boot ignores
unknown `agent-client.claude.trace-*` properties, it does not reject them.

## Explicit permissions and available tools

Pass provider options through the Client facade as well as the model. Facade request options
have precedence; `AgentClient.create(model)` supplies portable defaults, including auto-approval,
and does not inherit a model's permission choice. For a built-in read-only tool set:

```java
var readTools = List.of("Read", "Glob", "Grep");
var options = ClaudeAgentOptions.builder().model("claude-sonnet-4-6")
    .yolo(false).tools(readTools).allowedTools(readTools)
    .disallowedTools(List.of("mcp__*")).timeout(Duration.ofMinutes(3)).build();
try (var model = ClaudeAgentModel.builder().defaultOptions(options)
        .capture(capture).workingDirectory(workspace).build()) {
    var response = AgentClient.builder(model).defaultOptions(options).build()
        .goal(prompt).workingDirectory(workspace).run();
}
```

Explicit `yolo(false)` maps to CLI permission mode `default`; true retains dangerous bypass.
`tools` selects available built-in tools: null retains CLI defaults, empty disables them, and
names select that set. `allowedTools` supplies approval rules; omitting a tool from those rules
does not remove it. `disallowedTools` supplies deny rules. Built-in tool selection does not restrict
MCP tools, so the example separately denies `mcp__*`. These controls do not constitute filesystem
sandboxing. Inspect actual CLI init and acquisition evidence for the deployed runtime's behavior.
