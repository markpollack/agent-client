# Codex one-shot lifecycle

`agent-client-codex` adapts one fresh `codex exec` to `AgentClient`'s asynchronous lifecycle. It preserves the legacy `CodexAgentModel` and SDK entry points. The current capture contract targets Codex CLI 0.166.0; deterministic subprocess tests establish the adapter behavior, not live provider qualification.

```java
var provider = CodexClientProvider.builder()
    .codexPath("/absolute/path/to/codex")
    .workingDirectory(workspace)
    .acquisitionDirectory(captures, "0.166.0")
    .build();
try (var client = AgentClient.builder(provider)
        .defaultWorkingDirectory(workspace)
        .defaultTimeout(Duration.ofMinutes(3))
        .build()) {
    var execution = client.goal("Describe this workspace").runAsync();
    var response = execution.result();
    var termination = execution.awaitTermination(Duration.ofSeconds(5));
}
```

The child receives the effective request working directory, explicit sandbox and noninteractive approval flags, `--no-daemon`, `exec --ignore-user-config --json`, and the exact UTF-8 goal through stdin (`-- -`). The default sandbox is read-only. `sandbox(...)` explicitly selects another native sandbox; `skipGitCheck(true)` is optional. Existing local authentication is used. The adapter neither copies credentials nor changes shared configuration. User rules remain enabled. Model, effort, environment and timeout come from `AgentOptions`.

Cancellation targets the one owned invocation with either interruption flag. Client deadline arbitration and bounded observed-process/reader cleanup are separate from the provider's reported result. Cleanup tracks the direct process and observed descendants; it does not certify process-group containment or rule out escaped children. There is no replay, retained session, history loading or native progress capability. Unsupported portable max-turn, schema, system-instruction, MCP, extra-option and interactive approval requests fail explicitly before launch. No hard dollar cap or internal model-turn bound is claimed. Reported token usage is retained as source observations; no price or missing quantity is inferred.

To materialize acquisition, configure an `ExecutionRecorder` and override its `prepare(decision, evidence)` method. `evidence.get("codexAcquisition")` supplies a `CodexAcquisition`; `writeManifest(decision)` freezes the exact owning decision/session attribution. This optional recording lane runs after result acceptance. A plain decision function does not request source materialization. Keep the original decision and manifest bytes for Journal's independent qualifier and final archive attachment. Acquisition completeness is distinct from Journal acceptance.

Original stdin, stdout and stderr are captured independently before decode or redaction, with a 4 MiB retained limit per stream. Overflow remains explicit; truncated stdout cannot produce a successful response. Native rollout acquisition is bounded to 16 MiB and a 20,000-entry inventory. It selects only a new exact-thread filename whose session metadata matches the observed stdout identity, cwd, CLI version and exec route. Preexisting, ambiguous, missing, symlinked or mismatched sources remain incomplete. There is no latest-file fallback. Use `nativeSessionsDirectory(...)` when the native session root differs. Natural EOF and observed byte totals remain distinct from forced closure. Source files may contain sensitive task data; choose an application-owned private capture directory.
