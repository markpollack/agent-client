# Recording Junie runs with Agent Journal 2.0

`agent-client-junie` runs JetBrains Junie on the Client lifecycle and seals each run's native session for Agent
Journal. Each execution is one fresh Junie ACP session, driven through the same `AcpAgentModel` and `JunieAcpProfile`
as `JunieAgentModel`. The supported path is a synchronous `AgentClient.run()`. Retained sessions are refused.

```java
var provider = JunieClientProvider.builder()
    .workingDirectory(project)
    .acquisitionDirectory(Path.of("captures"))   // seal each accepted run here
    .build();
try (var client = AgentClient.builder(provider)
        .defaultWorkingDirectory(project)
        .executionRecorder(new JournalExecutionRecorder(Path.of("archive"), "my-app"))
        .build()) {
    AgentClientResponse response = client.goal("Create hello.txt containing hello").run();
}
```

## What is sealed

Junie names its native session directory (`~/.junie/sessions/<id>`) with the ACP `session/new` id. After the run,
Client copies that directory's `summary.json` and `events.jsonl`, hashes them, and writes `acquisition.json` beside
the copies. The manifest uses Agent Journal's profile `junie-<version>-acp-session-files`. `<version>` is the build
number Junie reports in ACP `initialize`, for example `3813.7` for Junie 26.10.20. The manifest also records:

- the session directories that appeared during the run, which must be exactly that one;
- the ACP stop reason;
- the task id of the single `TaskStartedEvent`;
- whether every process the run started was seen to end.

Any check that fails marks the generation incomplete and names the reason. It never fails the run.

`JournalExecutionRecorder` then selects Journal's qualifier by that profile:
- **With Journal's Junie qualifier** on the classpath, the run is attached under `client-terminal-junie-acp/1` and
  assembled by `FinalAssembly`. The archive can be verified independently with Journal's reader and `AssemblyOracle`.
- **Without the qualifier,** for example against Journal 2.0.0, the run is archived evidence-only and reported
  `UNQUALIFIED`. The execution result is unaffected either way.

Usage and cost are what Junie's summary reports. A cost of zero, for example from a local model, is valid, and a
token count Junie does not report stays absent.

## Environment and credentials

Junie 3813.7 writes the environment it was launched with into `events.jsonl`. This provider therefore never passes
on the JVM's environment. Junie gets:
- a small allowlist: `PATH`, `HOME`, `USER`, `LOGNAME`, `SHELL`, `LANG`, `LC_ALL`, `LC_CTYPE`, `TERM`, `TMPDIR`,
  `TZ`, `JAVA_HOME`, the `XDG_*` directories and `DBUS_SESSION_BUS_ADDRESS`;
- the variables the options name.

A session that still records a variable whose name looks like a credential (`KEY`, `TOKEN`, `SECRET`, `PASSWORD`,
`CREDENTIAL`, `AUTH` and similar) is withheld: it is not copied. Values after options such as `--auth` or
`--openai-api-key` are redacted from the recorded command.

Do not pass credentials as environment variables in the options when recording: they would be withheld, and the run
would be recorded without its session.

## Local models

Junie reaches an OpenAI-compatible server through a custom model profile. Put a `<name>.json` file in a folder,
pass that folder as the option extra `model-location`, and select the model `custom:<name>`. `baseUrl` is the full
chat completions endpoint:

```json
{
  "baseUrl": "http://localhost:1234/v1/chat/completions",
  "apiType": "OpenAICompletion",
  "id": "qwen/qwen3-coder-30b",
  "displayName": "Local qwen3-coder-30b",
  "apiKey": "local"
}
```

The server must accept Junie's full prompt, which exceeds 12,000 tokens for a simple task. Ollama's default context
of 4,096 tokens truncates it.

## Tests

- `JunieClientTest`: an offline ACP fixture that writes a 3813.7-shaped session directory. It covers sealing, the
  incomplete cases, the withheld credential and the launch environment.
- `OneShotRecordingConformanceTest`, row `JUNIE_ONE_SHOT`: the shipped recorder and the independent reader.
- `JunieJournalRecordingIT`, live, run with `-Pfailsafe` and skipped without a Junie CLI. Its environment variables
  name the executable, the model and the model folder.
