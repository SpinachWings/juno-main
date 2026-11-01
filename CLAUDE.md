# Juno v2

Desktop orchestrator for coding agents. The spec is `docs/design.md`; it is built one milestone per session from
`docs/build-prompts.md`, all on the `v2` branch.

**Read `docs/progress.md` first**: what's done, stubbed and decided, and what the next milestone needs to know.

## Conventions
- Java 27, JavaFX 27, Maven, Jackson. RichTextFX, commonmark-java, java-keyring (design §2). Windows first;
  macOS/Linux should work.
- Package layout (design §10), root `dev.juno`:
  - `app`: JavaFX entry point, main window, toolbar, board/notes toggle
  - `model`: profiles, projects, keys, agents, links, settings (plain data, JSON)
  - `store`: `~/.juno` persistence: settings, agents, journals, logs
  - `provider`: `Provider` interface + `AgentEvent` types; `provider.claude`: `ClaudeCodeProvider` (command line,
    stream-json parser, hook installer, MCP config)
  - `runtime`: `AgentRuntime`, `AgentProcess`, `Journal`
  - `comms`: `JunoMcpServer`, links, delegation/approvals, reports, loop guard, pipeline polling
  - `git`: agent worktrees, review snapshots, credential helper, `/diff`
  - `prompts`: built-in prompt templates (§11)
  - `skills`: skill library, bundled packs, per-agent plugin folders
  - `ui`: board, panes, output blocks, setup form/options menu, settings tabs, notepad, filter list
- All data under `~/.juno`, overridable with `JUNO_HOME`. Every write is atomic (temp file + move).
- Tokens are never logged, shown in full, or put in prompts.
- All text sent to agents lives in `docs/prompts/` (format in `docs/prompts/README.md`), never inline in Java.
- Palette defined once at the top of one stylesheet.

## Build, test, run
Set up in Milestone 1; keep these commands true.

```bash
mvn verify
```

```bash
mvn test
```

Run against a scratch home, never the real `~/.juno` (PowerShell):

```powershell
$env:JUNO_HOME = "$env:TEMP\juno-scratch"; mvn javafx:run
```

## Working rules
- Build only the current milestone; stub later milestones' parts with a TODO and a line in `docs/progress.md`.
- Never point agents or tests at real repositories or the real `~/.claude`; use scratch copies.
- Agent behaviour is checked against the real Claude Code CLI with haiku and low effort.
- Spec wrong, ambiguous or impossible → stop and ask. Record every decision the spec didn't make in
  `docs/progress.md`.
- One commit per milestone: "Milestone <N>: <name>". Don't push.
