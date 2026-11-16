# Junie 3813.7 live ACP session, sanitized

`summary.json` and `events.jsonl` are copied byte for byte from Agent Journal's fixture
`journal-capture-tck/src/test/resources/ocsf/junie-acp-3813.7-live` (agent-journal `bf7665a3`). That fixture is a
real recording, not a constructed one: Agent Client sealed one fresh synchronous Junie 26.10.20 (3813.7) ACP session
on 2026-11-16, run on a local model, and Journal sanitized its paths. Journal's README lists each replacement. The
environment event holds only allowlisted variable names, and its values are Junie's ciphertext, unchanged.

`fake-junie-acp.py` in `replay` mode writes these files as the native session directory. It substitutes only the
session id (`session-261116-181916-vwh1`) and the project directory (`/work`), so a test meets a live session's real
shape, including the main agent's result block reported twice and the per-call `modelUsage` time.
