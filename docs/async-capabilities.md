# Async capabilities and migration

| Path | Supported behavior | Remaining qualification |
|---|---|---|
| Explicit `AgentExecutionProvider` through `AgentClient` | One-shot and retained async/sync lifecycle, bounded acquisition, cancellation, independent waits, cleanup reports and detached metadata | Each adapter must prove its transport contract |
| Native Claude | Existing synchronous model API | New Client adapter, exact native turn/generation ownership, containment/cessation, retained cancellation/reuse, load/reconnect/refresh and native capture |
| Other native providers | Existing synchronous APIs | Async/retained Client routes refuse until independently qualified |
| Journal evidence-only recorder | Frozen decisions, exact receiver ACK, conflict/uncertain delivery and independent reader | Native profile, source/turn/connection association and complete archives |

The current SDK `close()` return alone is insufficient evidence of descendant cessation. The
[fake-child probe](examples/sdk-cessation/SdkCessationProbe.java) demonstrates a TERM-ignoring
owned descendant surviving that return, with no inference or prompt. The probe kills its test
child in `finally`. Do not infer safe native teardown or exact-history reattachment from a closed
SDK status flag. Native async remains unsupported until the producer supplies adequate termination
facts and the adapter proves them.

`JournalExecutionRecorder` uses `journal-terminal-receiver/1`, `client-terminal-evidence/1`, and
`client-terminal-evidence-only/1`. Pending/unavailable native evidence is explicit. An accepted
terminal receipt is not a complete native archive. The receiver's current `IOException` does not
distinguish integrity failure from lost reply, so automatic retries are disabled for that class.
Applications can retain the prepared job and explicitly retry identical bytes when appropriate.

Migration notes:

- `result(Duration)` and `awaitTermination(Duration)` include lock acquisition in the caller budget.
- `close()` can now throw `CleanupUnconfirmedException`; inspect its cleanup handle and retain
  uncertainty instead of treating return from a stop request as proof of cessation.
- Standalone setup failures use `AgentOperationException`; setup timeout and interruption are distinct.
- Typed native metadata remains typed after copying; opaque unsupported objects still fail honestly.
- Legacy public model sessions/streaming APIs remain present. Their removal and native migration
  must accompany a qualified replacement; this increment is not a public API freeze.

Release acceptance still requires a qualified native adapter, native/source recording profile,
installed consumer migration, full applicable native/default checks, and a separate release decision.
