# Sealed Claude archives with a child core

Copied unchanged from Journal, `journal-capture-tck/src/test/resources/ocsf/terminal-child` at agent-journal
commit `17e80845` (Journal re-sealed them in `3bac7e8` and `397ec12`; TCK `2.0.0-20261107.150003-6` and later read
these, not the `aa75c022` copies). Do not regenerate them here. The verdicts are Journal's own, from its
`TerminalChildScopeTest` at that commit.

| Case | Child cores | Journal's acceptance |
|---|---|---|
| `claude-sub-agent` | `children/toolu_AGENT`, an `Agent` sub-agent | ACCEPTED |
| `claude-sub-agent-nested` | `children/toolu_AGENT` and `children/toolu_INNER`, a sub-agent that spawns a sub-agent | ACCEPTED |
| `claude-heartbeat-bd04735` | `children/toolu_MCP`, a phantom child the bd04735 writer made of a `tool_progress` heartbeat | DEFECTIVE |
| `claude-heartbeat` | none (the control) | ACCEPTED |

Each archive's decision is `executionId=execution-1`. Its assembly expectation is `assembly-expectation.json`
beside `store`, not the recorder's `native-<executionId>` copy.
