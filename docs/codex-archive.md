# Installed Codex archive fixture

The [Codex caller](examples/CodexArchive.java) runs a synthetic child through the installed
`CodexClientProvider`, then sends its exact sealed capture through Journal's independent qualifier
and terminal receiver. It uses the actual immutable Client decision, encoded by the public Journal
recorder and read through the public binding reader. The original evidence-only package remains
unchanged; the completed Codex archive receives its own initial binding in a separate store.
This example uses `client-terminal-codex-0.166.0/1` with initially `COMPLETED` acquisition.

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
