# Async capabilities and migration

| Path | Supported behavior | Remaining qualification |
|---|---|---|
| `AgentExecutionProvider` through `AgentClient` | One-shot and retained lifecycle, bounded acquisition, exact cancellation, independent result/termination waits and detached metadata | Each adapter proves its own transport contract |
| `ClaudeClientProvider` | SDK-backed one-shot, retained follow-up, observed-resource teardown, exact-history reconnect, immutable binding refresh and bounded progress | Cross-handle/restart loading, broader process containment |
| Other native providers | Existing synchronous APIs | New async/retained Client routes require separate qualification |
| Journal default recorder | Frozen decisions, exact receiver ACK, conflict/uncertain delivery and independent reader | Native sources are unavailable |
| Journal explicit Claude recorder | One-shot Claude Code2.1.316 source qualification, exact execution/connection association and independently verified tool-bearing archive readback | Retained/reconnected archives and other profiles remain unqualified |

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
- Legacy public model sessions/streaming APIs remain present. This is not a public API freeze.

Broader provider qualification and release acceptance remain separate from this Claude integration.
