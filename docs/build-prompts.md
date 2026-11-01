# Juno v2 — build prompts

One prompt per session. Start each milestone in a **fresh session**, opened in `juno-main` on the `v2` branch. Paste
the shared preamble first, then the milestone's block.

## Before Milestone 0

- Done: v1 is on GitHub (`main`); the `v2` branch starts empty apart from `LICENSE`, `.gitignore`, `.gitattributes`,
  `docs/design.md` and this file.
- Still to do (you): create a sandbox GitHub repository and a sandbox GitLab project you don't mind agents commenting
  on, each with a simple pipeline (e.g. one job that runs `echo ok`). They're needed for Milestone 5a.

## Shared preamble (paste at the top of every milestone prompt)

```
We're building Juno v2 from the spec in docs/design.md, one milestone per session.

Read first: CLAUDE.md, docs/progress.md, and the docs/design.md sections listed below — only those, unless something
there points elsewhere.

Rules for every milestone:
- Build only this milestone's scope. Anything it touches from a later milestone gets a clear stub/TODO and a line in
  docs/progress.md. Don't build ahead.
- Done means: it compiles; unit tests for the logic you wrote pass; and you have checked it yourself:
  - the app, run against a scratch JUNO_HOME (never my real ~/.juno), with a screenshot of each new screen;
  - any agent behaviour, run against the real Claude Code CLI using haiku and low effort to keep it cheap;
  - never point agents or tests at my real repositories.
- If the spec is wrong, ambiguous or impossible, stop and ask me before working around it. Record every decision you
  make that the spec didn't, in docs/progress.md.
- At the end: update docs/progress.md (done / stubbed / decisions / known issues / what the next milestone needs to
  know), then make one commit on a branch named milestone-<N>. Don't push.
```

## Milestone 0 — prompts (planning, no code) · Opus

```
Milestone 0: write the natural-language parts of Juno.

Read docs/design.md §4, §6, §8 and §11 (skim the rest for context).

Draft, for my approval, section by section:
1. The system prompts of the six built-in profiles (§6.1): orchestrator, coder, reviewer, planner, searcher,
   notes writer. They must implement every rule §6.1–§6.4 gives each profile (e.g. the coder's done-sequence, the
   linked-reviewer message, /checkforcomments behaviour; the reviewer's checklist and approval-before-posting; the
   planner's pane-only output and superpowers focus).
2. The coder's /checkforcomments profile command template (§6.2).
3. The built-in templates of §11: the agent preamble; every Juno MCP tool's description and parameter descriptions
   (§4.4); refusal and result messages; the wrappers for agent messages, tasks, /hey info, reports and digests,
   approval outcomes, link announcements, loop-guard notice and pipeline-wait outcomes; the resume recap.

Keep prompts direct and specific; no filler. Show me each section and wait for my approval or changes before the
next. When all are approved, write them to docs/prompts/ (one file per prompt or template, with {{placeholders}}),
create CLAUDE.md (conventions: Java 27, JavaFX 27, Maven, package layout from §10; how to build, test and run;
"read docs/progress.md first") and docs/progress.md, and commit.
```

## Milestone 1 — foundation and settings · Sonnet

```
Milestone 1: foundation, settings and first run.

Read docs/design.md §2, §3, §5 (all of it), §6 (the profiles table only), §10 (version check and code layout), §12.

Scope:
- Maven project, module setup, package layout (§10), theme and bundled fonts (§2), main window shell with the toolbar
  (buttons may be stubs).
- model + store: all settings as plain data, atomic JSON writes under JUNO_HOME.
- Settings window with all six tabs working and saved:
  - Keys: add/edit/delete, java-keyring storage with env:VARIABLE fallback (never plain text), masked tokens,
    MCP URL from host + override. Test button stubbed (real in Milestone 5).
  - System settings: everything in §5.2, with the Claude hook install / uninstall / status fully working
    (merge, idempotent, timestamped backups — test against a scratch ~/.claude copy, never my real one). Storage
    section stubbed.
  - Projects: fields incl. build & test commands, validation.
  - Agent profiles: the settings table with 🔒 locks on built-ins, Duplicate, delete for own profiles; the six
    built-ins with their defaults and the system prompts from docs/prompts/.
  - Skills: library import (SKILL.md / folder / zip / .skill), whole-orchestration or per-profile assignment,
    per-profile skill toggles, bundled superpowers with its locked default assignments, refreshed on start-up.
  - Commands: the reference page (from a single command registry the later milestones extend).
- First-run setup (§5.1.2) and the Claude Code version check (§10).
No agents run yet.
```

## Milestone 2 — one agent, end to end · Opus

```
Milestone 2: a single agent running for real, shown in one pane.

Read docs/design.md §3, §5.5, §7.3–§7.5, §7.7, §8.1 (excluding the comms commands), §10, §11 (preamble, resume
recap), §12.

Scope:
- Provider interface + ClaudeCodeProvider: command line (§10), stream-json in/out, events
  (message, thinking, tool use, diff, sub-agent, usage, result, rate limit), resume.
- AgentRuntime / AgentProcess: statuses incl. reason tags and unread (§3), status from output events, hook events
  when the hook is installed, idle process timeout, the journal, crash recovery and Resume with recap.
- Skills given as a session-only --plugin-dir; system prompt = preamble + profile prompt.
- A minimal Juno MCP server with only request_permission, wired as --permission-prompt-tool: the four answers, the
  editable rule, per-agent rules (stored; the UI list comes in Milestone 3), disallowed tools always win.
- Agents without a folder, needs-a-folder holding the first message, request_folder.
- The pane: rendered output blocks incl. nested sub-agents, selectable and copyable everywhere, context meter,
  usage-limit meter in the toolbar, input box with autocomplete.
- Commands: /stop /resume /new /compact /clear /copy /diff /usage /model /effort /activate<skill> /approve /deny
  /help, plain-message refusal while working, "isn't supported" for unknown commands.
Verify with real haiku runs: an edit shown as a diff, a permission prompt answered each of the four ways, a
mid-run kill and Resume.
```

## Milestone 3 — the board · Sonnet

```
Milestone 3: the board, many agents.

Read docs/design.md §3.1, §5.2 (Storage), §7.1, §7.2, §7.6, §7.8, §7.9, §7.10.

Scope:
- Layout: split right/down with the in-cell setup form, no empty panes, no resizing, drag-to-swap, close = hide,
  minimum sizes and scrolling, maximise, layout saved. Juno's pane reserved (Juno herself arrives in Milestone 4).
- Pane top bar and the ⋯ options menu (all settings of §7.10, applied from the next message; per-agent rules list
  with Remove; conversations; usage; delete with confirmations).
- Filter list, unread dots, colours, inbox (with its Waiting section), Queued status and the concurrency cap
  (Waiting agents excluded).
- Worktrees for agents that edit files, branch-in-use handling, and the protected-branch git hooks (per-worktree
  core.hooksPath, chaining the repo's own hooks).
- Earlier conversations and data limits; Storage section working; full agent deletion (asking before deleting
  Claude Code transcripts).
Verify with several haiku agents on a scratch git repository: two editing agents in separate worktrees, a refused
commit to main, hide/show/swap/maximise, a crash with several agents and recovery.
```

## Milestone 4 — Juno and communication · Opus

```
Milestone 4: Juno and agent-to-agent communication.

Read docs/design.md §4 (all), §8.2, §11.

Scope:
- Juno: the orchestrator agent always present (can't be deleted, hidden or closed; movable).
- The full Juno MCP server: every tool in §4.4, tool access by profile allowed tools, every rule enforced
  server-side.
- Links: /cantalkto, /stoptalkingto, via Juno, Juno's suggestions held for approval, announcements, can-talk-to line.
- Delegation: direct, named (/assign without Juno tokens), unnamed with approval cards; continue vs fresh with
  fresh-start always approved; "Juno asks before assigning" and "Tell Juno about direct tasks" settings.
- Capped reports, the review exception, digests, loop guard with hop limit and reset.
- /hey from any pane, /getcontext, /getcontextfrom, /status, /broadcast, context summaries from journals.
Verify with haiku agents: an unnamed task proposed and approved, a fresh-start approval, a link opened via Juno and a
refused unlinked message, a hop-limit stop.
```

## Milestone 5a — GitHub/GitLab capability check (spike, no product code) · Sonnet

```
Milestone 5a: verify what GitHub's and GitLab's own MCP servers support, before we build on them.

Read docs/design.md §5.1, §6.2–§6.5, §13.

Using my sandbox GitHub repository and sandbox GitLab project (I'll give you the names and add keys in a scratch
JUNO_HOME), check every item in §13's "to verify" list with small throwaway scripts or direct MCP calls:
inline comments anchored to a commit, summary comments, replying to and resolving threads, pipeline/check status per
commit incl. required checks and "waiting for approval", opening a PR/MR, exact names of approve/merge/close and
resolve-thread tools, and whether GitLab's MCP server accepts a personal access token.

Ask me before anything that posts, opens or changes something on the sandbox. Write the findings into
docs/progress.md as a table (capability → MCP tool name, or "missing → REST endpoint to use instead"), and list any
spec changes this forces for my approval. Don't change docs/design.md without my OK.
```

## Milestone 5 — GitHub/GitLab, PRs and reviews · Opus

```
Milestone 5: GitHub/GitLab access, the coder's PR flow, and reviews.

Read docs/design.md §5.1, §5.3, §6 (all), and docs/progress.md's capability table from Milestone 5a.

Scope:
- Keys on agents: per-agent mcp.json (deleted on exit), the per-process git credential helper, SSH→HTTPS, git
  identity per worktree, and the "no key, no host access" enforcement (GIT_ALLOW_PROTOCOL=file, no credential
  helpers or SSH agent, gh/glab disallowed, 🔑 stops). Test button for real.
- Coder flow: local build & tests, push, open PR/MR via MCP, the linked-reviewer message; /checkforcomments exactly
  as §6.2.
- Reviews: start_review/end_review — target resolution, "clone it yourself" prompt, Juno-side pipeline polling with
  every outcome in §6.3, Waiting ⏳ status, snapshots (§6.5: local commit or PR ref fetch, refs/juno namespace,
  submodules/LFS, lock retries), approval before posting, marker, summary with reviewed commit, re-review and
  resolving its own threads, clean-up. Use REST where 5a found MCP missing.
Verify end to end on the sandbox repositories (ask me before anything is posted): a coder opening a PR, a linked
reviewer waiting for the pipeline then posting approved comments, /checkforcomments handling them, a re-review, and an
agent with no key being refused.
```

## Milestone 6 — notepad · Sonnet

```
Milestone 6: the notepad.

Read docs/design.md §9 and §5.2 (notes folder).

Scope: board / notes toggle; note view with the notes folder's file tree, tabs, Markdown editor with live preview,
create / rename / move / delete; the notes-folder overlap checks; one agent's terminal beside the notes (the same
agent, still on the board). Verify with a scratch notes folder.
```

## Milestone 7 — hardening · Opus

```
Milestone 7: make sure what we built is what the spec says.

1. Read docs/design.md end to end and compare it with the implementation. List every gap or difference
   (missing, different, or added without being in the spec), and fix the ones I approve.
2. Run /code-review high on the whole branch and fix the confirmed findings.
3. Do a full manual pass in the running app (scratch JUNO_HOME, haiku agents, sandbox repositories): first run,
   every settings tab, every command, every status and reason tag, crash recovery.
4. Write the README (what Juno is, requirements, first run, the workflows, where data lives).
```

## Tips

- **If a milestone runs long** (most likely 2, 4 or 5), stop at a natural seam, record it in docs/progress.md, and
  continue in a new session with: "Continue Milestone <N> from docs/progress.md."
- **Between milestones**, run the app yourself for a few minutes. Things you notice go into the next prompt as
  "Also fix: …".
- **Model choice** (Opus for 0, 2, 4, 5, 7; Sonnet for 1, 3, 5a, 6) keeps your usage down where the work is routine.
