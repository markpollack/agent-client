# Sealed real Claude archives, with and without a child core

Copied unchanged from Journal. Do not regenerate them here: the verdicts are Journal's own. All are scrubbed real
Claude Code 2.1.316 runs (see each case's `SCRUBBED.md`), sealed in the Journal 2.0 formats (layout 1.0.0, extension
`agentic_cli` 1.0.0) under `client-terminal-claude-sdk/1`. Source: `journal-capture-tck/src/test/resources/ocsf/terminal-real/`
at agent-journal `e161c779`.

| Case | Execution | Child cores | Journal's acceptance |
|---|---|---|---|
| `claude-sub-agent` | `e52ccb3f-a65a-41b5-aaee-b8ca926d0452` | `children/toolu_01KQMhaSZMrxp98fqQBkmHCC`, a general-purpose sub-agent with its own acquired session and meta file | ACCEPTED |
| `claude-sub-agent-background` | `112cb044-70f3-415a-bd8f-b04fb9e473a3` | `children/toolu_01188QcnH2XjpZ9cF6knKaHy`, a background sub-agent still running when the parent's result was written | INCOMPLETE: its session file had not finished (`acquisition.child_session`). Kept as the negative control |
| `claude-heartbeat` | `f7b8944b-5b2a-4e5c-853a-2b1912d21e49` | none (the control) | ACCEPTED |

Each assembly expectation is `assembly-expectation.json` beside `store`, not the recorder's `native-<executionId>`
copy. The earlier hand-written cases (a nested sub-agent and the `bd04735` phantom heartbeat) are gone: Journal deleted
its synthetic cases, and 2.0 reads only the 1.0.0 formats.
