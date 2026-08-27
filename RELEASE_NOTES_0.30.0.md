# Agent Client 0.30.0

Adds **Junie** and **Grok over ACP**, extracts a shared `AcpAgentModel`, opens the TCK's provider
extension point, and records the turn ceiling in Claude traces.

## ⚠️ BREAKING — `Provider` is no longer an enum

`io.github.markpollack.agents.tck.Provider` changes from an enum to a holder of `String` constants,
and `@ProviderCapability` now takes `String[]`.

```java
// before — 0.29.3 and earlier
public enum Provider { CLAUDE, CODEX, GEMINI, AMAZON_Q, AMP, QWEN_CODE, GROK, ANTIGRAVITY }

// after — 0.30.0
public final class Provider {
    public static final String CLAUDE = "CLAUDE";
    public static final String CODEX  = "CODEX";
    …
}
```

**Affected artifact**: `io.github.markpollack:agent-tck` only. **Affected versions**: 0.30.0 and
later; every 0.29.x and earlier is unaffected. `agent-tck` is not managed by `agentworks-bom`, so
you are only exposed if you depend on the coordinate directly.

### What breaks, and what does not

The constants kept their names and are compile-time `String` constants, so **most usage compiles
unchanged**:

| Usage | 0.30.0 |
|---|---|
| `Provider.CLAUDE` as a value | ✅ unchanged |
| `@ProviderCapability({Provider.CLAUDE})` | ✅ unchanged |
| A variable, parameter or return **typed** `Provider` | ❌ becomes `String` |
| `Provider.values()` / `.name()` / `switch` on the enum | ❌ no longer available |

**Migration**: change the declared type, not the references.

```java
// before
@Override protected Provider getProvider() { return Provider.CLAUDE; }
// after
@Override protected String getProvider() { return Provider.CLAUDE; }
```

In this repository the change touched **five parity integration tests, and only their return type**.

### Why

A provider key becomes a plain string, so an adapter maintained **outside** this repository can
declare its own and take part in the parity matrix on equal terms. An enum made the compatibility
kit closed to exactly the third parties it exists to serve.

## New providers

**Junie** — speaks the Agent Client Protocol, so it needs no provider SDK; `acp-core` is the
transport. Wired to Agent Journal via the new `junie-cli-capture` (agent-journal **1.10.0**), with an
integration test proving a journal is produced.

**Grok over ACP** — moved onto the shared model, and a duplicate discriminator fixed.

## `AcpAgentModel` — one model, per-CLI profiles

Extracted from Junie and proven by a second consumer. An ACP CLI is now a **profile**, not a module:

```java
AcpAgentModel.builder(new MyCliAcpProfile()).build();
```

Do **not** subclass `AcpAgentModel`. ACP agents diverge on five independent axes — launch, auth,
trajectory address, capture parser, vendor `_meta` — and none is an inheritance boundary.

**Where ACP agents actually differ is trajectory location**, not the protocol. Three CLIs use three
addressing schemes; `AcpTrajectoryLocators` carries all three. Verify yours by running with the
process working directory deliberately different from the `session/new` one — Grok keys on the
session cwd, and code assuming otherwise yields **silently empty captures rather than an error**.

## The turn ceiling is now recorded

`ClaudeAgentModel` passes `maxTurns` to `SessionLogParser`. Before this, every Claude trace recorded
`maxTurns = -1`, so a run reporting `numTurns = 55` gave no way to tell "finished" from "cut off at
the ceiling" — two different processes recorded identically.

**If you hold traces produced by 0.29.3 or earlier, their `maxTurns` is `-1` and not recoverable.**
`stopReason` is the only remaining signal, and only when it happens to say `NATURAL_DONE`.

⚠️ **One path this does not reach.** `BaseRunRecorder` takes `maxTurns` from the `PhaseCapture`, so
anything that re-parses raw messages with its own `SessionLogParser.parse(...)` call still records
`-1` unless it passes `maxTurns` too. If you build captures yourself rather than taking the model's,
update those call sites.

## Dependencies

`agent-journal` moves **1.8.2 → 1.10.0** (`junie-cli-capture` is new there). Jackson, Reactor and
Spring AI are unchanged.

## Known limit, carried forward from agent-journal 1.9.0

Per-turn `outputTokens` and `thinkingTokens` do not reconcile with phase totals — measured at 388
against 35,416, and 0 against 8,501. Input and both cache figures reconcile exactly after
de-duplicating by `messageId`, and the tool-to-turn linkage is sound. **A per-tool dollar figure
cannot be computed correctly yet**; the reference cost is driven by output and thinking tokens.
