# Sealed Claude archives with a child core

Copied unchanged from Journal. Do not regenerate them here. The verdicts are Journal's own.

| Case | Source | Child cores | Journal's acceptance |
|---|---|---|---|
| `claude-sub-agent` | `journal-capture-tck/src/test/resources/ocsf/terminal-real/claude-sub-agent` at agent-journal `a83cad8` (`TerminalRealRecordingTest`, re-sealed there with the child assembly declared): a scrubbed real Claude Code 2.1.316 run, see `SCRUBBED.md` | `children/toolu_01KQMhaSZMrxp98fqQBkmHCC`, a general-purpose sub-agent with its own acquired session and meta file | ACCEPTED |
| `claude-sub-agent-nested` | `ocsf/terminal-child` at agent-journal `17e80845` (synthetic) | `children/toolu_AGENT` and `children/toolu_INNER`, a sub-agent that spawns a sub-agent | INCOMPLETE: neither sub-agent has an acquired session file (`acquisition.child_session`, required since agent-journal `6b9e6ec`). Kept as a negative control |
| `claude-heartbeat-bd04735` | `ocsf/terminal-child` at `17e80845` | `children/toolu_MCP`, a phantom child the bd04735 writer made of a `tool_progress` heartbeat | DEFECTIVE |
| `claude-heartbeat` | `ocsf/terminal-child` at `17e80845` | none (the control) | ACCEPTED |

The real case's decision is `executionId=e52ccb3f-a65a-41b5-aaee-b8ca926d0452`; the synthetic cases' is
`executionId=execution-1`. Each assembly expectation is `assembly-expectation.json` beside `store`, not the recorder's
`native-<executionId>` copy.
