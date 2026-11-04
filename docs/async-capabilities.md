# Async capabilities and migration

| Path | Supported behavior | Remaining qualification |
|---|---|---|
| `AgentExecutionProvider` through `AgentClient` | One-shot and retained lifecycle, bounded acquisition, exact cancellation, independent result/termination waits and detached metadata | Each adapter proves its own transport contract |
| `ClaudeClientProvider` | SDK-backed one-shot, retained follow-up, observed-resource teardown, exact-history reconnect, immutable binding refresh and bounded progress | Cross-handle/restart loading, broader process containment |
| `CodexClientProvider` | One fresh `codex exec` per one-shot execution; retained sessions resume the exact emitted thread with a replacement `codex exec resume` process per turn; original byte capture, exact-thread rollout prefix continuity, cancellation, deadline and quarantine handling | Deterministic subprocess qualification for CLI 0.166.0 (two-turn retention, two independent sessions, stale stop isolation, quarantine after incomplete turns); live resume behavior and retained archive acceptance remain separate. Loading and progress are unsupported |
| `AntigravityClientProvider` | One fresh `agy` print execution, original stdout/stderr and argv-prompt acquisition, cancellation/deadline and observed-resource cleanup | Deterministic installed proof; native and Journal SDK-route archive qualification pending. Retention, loading and progress unsupported; legacy Antigravity resume remains unchanged |
| Other native providers | Existing synchronous APIs and provider-specific SDKs | New async/retained Client routes require separate qualification |
| Journal default recorder | Frozen decisions, exact receiver ACK, conflict/uncertain delivery and independent reader | Native sources are unavailable |
| Journal explicit Claude recorder | One-shot Claude Code 2.1.316 source qualification, exact execution/connection association and independently verified tool-bearing archive readback, including multiple permission denials with the repaired reader | A new native route needs its own qualification |
| Installed Codex archive caller | Actual immutable Client decision, original synthetic capture, independent qualification, separate complete binding and packaged reader acceptance; mismatched capture rejected | Real CLI 0.166.0 execution and archive qualification remain separate |
| Journal retained Claude recorder | Deterministic two-turn archive, original decisions, post-close attachments, replay and separate reader acceptance | Fresh live start is blocked: initialization provides no observed native identity/readable BEFORE prefix for the current profile. Reconnect and alternative fresh profiles remain unqualified |

For Codex, start with the [one-shot guide](../agent-client-codex/README.md) and
[installed archive fixture](codex-archive.md). Its fixture checks
cover emitted argv, exact working directory, original stdin/stdout/stderr, native identity rejection,
no replay, deadlines and both cancellation flags. They do not establish real CLI sandbox behavior,
subscription authentication during a task, or live Journal acceptance. Transport failures retain their
original cause and suppressed cleanup failures; unconfirmed cleanup remains visible through termination
and close. An already accepted deadline or cancellation decision is unchanged by later failures.

Start with the [installed native caller](examples/NativeClaude.java): `agent-client-claude` plus
optional `agent-client-journal`, a working directory and an explicit execution deadline. The
provider builder accepts `maxBudgetUsd`, tool selection and required native `preToolUse` permission
callbacks. Hooks belong to the application and participate in the SDK control protocol; they are
not optional progress observers. A callback invocation, valid emitted response and the native peer's
application of that response are three separate facts. [Recording example](examples/RecordedAsync.java)
shows provider-independent correlation.

Cleanup confirms the SDK's positively observed process/resource inventory. It does not prove
process-group containment or absence of escaped children. Unknown resources remain owned and
prevent safe reuse. The [subprocess probe](examples/sdk-cessation/SdkCessationProbe.java) asserts both
root and observed descendant cessation against the repaired SDK. Process death is not a substitute
for source completeness: native capture additionally requires natural stdout/stderr EOF.

`JournalExecutionRecorder` uses `journal-terminal-receiver/1` and `client-terminal-evidence/1`.
The default profile is `client-terminal-evidence-only/1`; the explicit Claude factory can select
`client-terminal-claude-2.1.316/1`. Acquisition copies at most4MiB of original transport bytes in
memory, then seals sources on the recording lane after result acceptance. Overflow, missing EOF,
missing native files and qualification errors are reported separately from the execution result.
Executions without a terminal native result record decisions with incomplete acquisition; partial
transport buffers are not a qualified archive. This profile admits one closed text-message
execution, not retained/reconnected history. Complete
native archive acceptance requires the independent Journal reader with protected original sources
and the qualifier's assembly expectation; a terminal ACK alone is insufficient.

`TerminalIntegrityException` is permanent and not retried. Other Journal I/O failures retain
acceptance uncertainty and are also not automatically retried. A failed response does not prove
publication never happened. Applications can retain a prepared delivery job and explicitly resend
identical bytes when appropriate, without replaying provider work.

The duplicate `subtype` serialization and Journal pre-init session-identity readback defects have
been repaired and consumed. Earlier artifacts and malformed captures remain historical evidence;
the [duplicate-field probe](examples/SdkControlResponseProbe.java) remains a compatibility check.
Use the repaired dependency selection described in [the Claude guide](native-claude.md).

The repaired SDK omits absent optional hook controls and preserves `hookSpecificOutput` with its
`PreToolUse` event and nested permission decision. Client subprocess tests separately prove allow
application and absence of a denied side effect. These tests do not qualify every native tool or
policy failure mode. Complete archive acceptance preserves what happened, including policy errors;
it does not certify that a deny was enforced.

On the pinned Claude Code2.1.316 route with the repaired hook serializer, a native case observed
three denied Bash calls and one denied out-of-workspace Read as explicit error results, with no
successful results for those tool IDs. This is case-specific policy evidence. The same task's
complete acquisition and receiver ACK initially failed independent archive normalization for multiple
permission denials. The repaired Journal reader now accepts the untouched original archive and a
fresh assembly with the original decision and binding. Each denial retains its own tool ID, value and
source reference; array position does not associate a denial with a tool. Use a reader containing that
repair. Archive acceptance remains distinct from policy enforcement and downstream task assessment.

A terminal provider non-success remains `STOPPED`; a closed stream without a terminal result fails
with `PROVIDER_TRANSPORT`. A hook rejection reported only on stderr followed by a successful native
result has no structured hook-rejection observation at the current SDK seam: Client reports the
provider result and retains available diagnostics in source capture. It does not parse stderr to
invent enforcement or claim that already executed work was prevented. SDK callback exceptions/null
returns produce its blocking response, but are not exposed as a distinct Client hook-failure result.
Applications needing stronger policy guarantees must qualify their native route; no universal
fail-closed hook guarantee is provided. Recording outcome stays independent in all these cases.

Migration notes:

- `result(Duration)` and `awaitTermination(Duration)` include lock acquisition in the caller budget.
- `close()` may throw `CleanupUnconfirmedException`; inspect its cleanup handle and retain uncertainty.
- Standalone setup failures use `AgentOperationException`; setup timeout and interruption are distinct.
- Typed native metadata remains typed after copying; unsupported opaque metadata fails explicitly.
- Legacy Claude/Codex model, session and streaming entry points and their underlying SDKs remain
  supported alongside these new routes. Their retirement requires a separate caller inventory and migration.

The [retained archive caller](examples/RetainedClaudeArchive.java) uses an observed, readable
pre-turn transcript and proves the existing two-turn profile with fixtures. It does not turn an absent
fresh-start transcript into an empty prefix. A separate fresh-start profile is proposed, not implemented
or live-qualified. Broader provider qualification and release acceptance remain separate.
