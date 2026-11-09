# Installed Codex archive fixture

The [Codex caller](examples/CodexArchive.java) runs a synthetic child through the installed
`CodexClientProvider`, then sends its exact sealed capture through Journal's independent qualifier
and terminal receiver. It uses the actual immutable Client decision, encoded by the public Journal
recorder and read through the public binding reader. The original evidence-only package remains
unchanged; the completed Codex archive receives its own initial binding in a separate store.
This example uses Journal's `TerminalReceiver.CODEX_PROFILE` (`client-terminal-codex-exec/1`) with
initially `COMPLETED` acquisition.

Select compatible, verified installed Client and Journal JARs, including Journal's Codex qualifier,
writer and independent reader. Snapshot coordinates alone do not identify their bytes: keep the
selected source revisions, JAR hashes and runtime classpaths with the proof. `CALLER_CP` contains
only installed runtime JARs for `agent-client-codex`, `agent-client-journal` and their dependencies.
`READER_CP` contains Journal's reader and its runtime dependencies, excluding Client, Codex SDK
and Journal writer JARs. Use separate output directories for the two programs.

```bash
javac -cp "$CALLER_CP" -d caller-classes docs/examples/CodexArchive.java
javac -cp "$READER_CP" -d reader-classes docs/examples/ReadCodexArchive.java
java -cp "caller-classes:$CALLER_CP" CodexArchive /absolute/new/proof /absolute/fixtures/codex-child.py
java -cp "reader-classes:$READER_CP" ReadCodexArchive /absolute/new/proof/proof.json
```

Copy `codex-child.py` and `codex-native-template.jsonl` from `agent-client-codex/src/test/resources`
to the fixture directory and make the script executable. The caller requires a fresh proof directory.
It checks that result acceptance is independent of recording delivery, confirms one launch, and
replays the exact envelope without changing decision, association, binding, ACK or original files.
The separate reader checks execution, decision, connection, native thread and turn association,
compares original source bytes, and runs Journal's full archive verification.

For a negative control, make a second fixture proof in another fresh directory. Pass that second
archive's `sources` directory as the optional second argument to `ReadCodexArchive` while keeping
the first proof. The reader must reject the mismatched capture. Journal's `OcsfReaderTool verify`
can independently check the same mismatch using the first events and assembly expectation and the
second sources/acquisition manifest. Preserve both positive inputs unchanged.

This proves deterministic integration. It does not qualify a real CLI execution, sandbox behavior,
subscription authentication during a task, or a retained Codex session. A real launch needs its own
explicit environment, executable, model, working directory, deadline and original-source verification.

## Retained two-turn caller

The [retained caller](examples/CodexRetained.java) runs two turns on one public `AgentSession` against
the same synthetic child, holds recording delivery until both results are accepted, then checks both
per-turn manifests and the thread-level `journal-codex-retained-generation/1` manifest the provider
writes at close (`captures/codex-thread-*/codex-thread.json` with `transcript.bin`): whole-line
`TRANSCRIPT` locators partition the final transcript, turn 1's snapshot is a prefix of turn 2's, and
`thread_token_usage(2) − thread_token_usage(1) == turn_token_usage(2)` on the fixture.

```bash
javac -cp "$CALLER_CP" -d caller-classes docs/examples/CodexRetained.java
java -cp "caller-classes:$CALLER_CP" CodexRetained /absolute/new/proof /absolute/fixtures/codex-child.py
```

This is fixture evidence only, but the fixture now follows what one native two-turn probe on codex-cli
0.166.0 observed (2026-11-04): the resumed process re-emits `thread.started` with the same thread id,
the global flags and `--model` are repeated on resume, the resumed rollout region starts with a
`thread_settings_applied` record carrying `thread_id` and `thread_settings`, and the resumed turn's
`turn.completed.usage` on stdout is the thread total (equal to the rollout's
`token_count.info.total_token_usage`), while the turn's own figure lives only in the rollout
(`token_count.info.last_token_usage` and `token_usage_record.turn_token_usage`). The thread manifest
therefore records `wire_usage` (cumulative on a resumed turn), `turn_token_usage` (per turn) and
`thread_token_usage` separately; Journal refuses a resumed turn whose wire usage is not the thread total.
