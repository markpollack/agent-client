# Sealed Claude archives with a child core

Copied unchanged from Journal, `journal-capture-tck/src/test/resources/ocsf/terminal-child` at agent-journal
commit `aa75c022fd6895cf1e764a0e8b3f4df9a8651fb8`. Do not regenerate them here.

| Case | Child core | Journal's acceptance |
|---|---|---|
| `claude-sub-agent` | `children/toolu_AGENT`, a real `Agent` sub-agent | INCOMPLETE (only the child's assembly assertions are unchecked) |
| `claude-heartbeat-bd04735` | `children/toolu_MCP`, a phantom child the bd04735 writer made of a `tool_progress` heartbeat | DEFECTIVE |
| `claude-heartbeat` | none (the control) | ACCEPTED |

Each archive's decision is `executionId=execution-1`. Its assembly expectation is `assembly-expectation.json`
beside `store`, not the recorder's `native-<executionId>` copy.
