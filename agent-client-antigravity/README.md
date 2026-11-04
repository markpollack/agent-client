# Antigravity one-shot Client provider

`AntigravityClientProvider` adds a fresh `agy --output-format stream-json --print`
execution to the common asynchronous `AgentClient` lifecycle. The legacy
`AntigravityClient` and `AntigravityAgentModel` remain unchanged, including their
existing resume entry point. This new provider rejects retained connections.

```java
var provider = AntigravityClientProvider.builder()
    .executable("agy")
    .workingDirectory(workspace)
    .acquisitionDirectory(captureRoot, "1.4.6")
    .build();
try (var client = AgentClient.builder(provider).defaultWorkingDirectory(workspace).build()) {
    var execution = client.goal("Read README.md and report its title.").runAsync();
    var response = execution.result();
}
```

The common lifecycle supplies cancellation, deadlines, close, immutable original
outcome and separate recording. Cleanup covers observed process resources;
unobserved process escape is not certified. Live progress streaming, retained
follow-up and reconnect are unsupported by this adapter. The supported options
are text, working directory, model, effort, environment, auto-approve and timeout;
unsupported portable controls fail before dispatch.

Original stdout/stderr are bounded at4MiB each, with explicit observed count,
EOF and overflow. The prompt travels in argv and is stored as `prompt-argument`,
separate from empty stdin. Capture happens on the secondary recorder lane and
cannot replace the original decision. `AntigravityAcquisition.writeManifest(D)`
checks E/G attribution and freezes the decision IDs. Version supplied to the
builder is an expectation, never an observation. No native turn identity is invented.

Reported native status/error remains separate from the existing SDK-derived
soft-denial/recovered-error outcome. Terminal usage is retained as reported and
missing cost is unknown. This deterministic provider does not establish native
or Journal archive qualification; the existing Journal CliCapture route does not
admit this new source association. The installed caller in
`docs/examples/AntigravityInstalled.java` preserves the original decision through
the existing evidence-only recorder and reports `archiveQualified=false`.
