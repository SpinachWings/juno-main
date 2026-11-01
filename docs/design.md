# Juno — design

> Build spec for a clean rewrite. Open items are in §13.

## 1. Goal

Juno is a desktop orchestrator for coding agents. You define **agent profiles** and **projects**, then run
**agents** side by side on a board of terminal panes. One agent, **Juno**, is always there: she orchestrates the
others — finding idle agents, handing them tasks (with your approval when you didn't say who), collecting their
reports, and linking agents when you ask.

The previous version (ticket board + prototype agent mode) is archived on GitHub. Don't port its code; keep its look
and the lessons in §12.

## 2. Stack & look

- Java 27, JavaFX 27, Maven, Jackson. Windows first; macOS/Linux should work.
- **RichTextFX** for code, diffs and terminal text (selectable, copyable, syntax highlighted); **commonmark-java** for
  Markdown (agent messages, notepad preview); **java-keyring** (OS vaults, via JNA) for tokens.
- Theme: near-black greys, light blue `#6FD6FF` and magenta `#FF4FD8` accents, square corners; Share Tech Mono for
  UI text, VT323 for titles and headings (bundled, SIL OFL). Palette defined once at the top of one stylesheet.
- All data under `~/.juno` (override with `JUNO_HOME`). Every write is atomic (temp file + move).

## 3. Terms and statuses

| Term | Meaning |
| --- | --- |
| **Agent profile** | Reusable definition of who an agent is and how it runs (§5.4). |
| **Project** | A local repository folder, optionally with one GitHub or GitLab remote (§5.3). |
| **Agent** | A running instance of a profile, optionally on a project. One pane on the board, with its own name, colour and conversation. |
| **Juno** | The one orchestrator agent (built-in `orchestrator` profile). Always present; can't be deleted or hidden. |
| **Worktree** | A separate `git worktree` checkout with its own branch, so agents that edit files never share a folder (§7.8). |
| **Key** | A GitHub or GitLab token (§5.1). |
| **Link** | Two-way permission for two non-Juno agents to message each other (§4.1). |
| **Context** | What an agent's conversation holds, in tokens, against its model's window. |
| **Provider** | The CLI behind a profile. Only Claude Code for now (§10). |

There are no organisations.

| Status | Meaning |
| --- | --- |
| **Idle** | Nothing to do. New agents start here; Done returns here after 15 minutes (fixed). |
| **Queued** | Has work but "max agents working at once" is reached; shows its place ("Queued · 2nd"). |
| **Working** | Busy with a message or task. |
| **Waiting ⏳** | Waiting on something outside Juno, e.g. a PR's pipelines ("⏳ pipelines on #42 · 12 min"). Spends no tokens; its process may be closed meanwhile. |
| **Awaiting decision** | Needs you, always with a reason tag: ❓ question · 🔐 permission · 📁 folder · 🔑 key · ⚠ failed · ⏸ interrupted. |
| **Done** | Just finished its task. |

Juno and Waiting agents never count towards "max agents working at once"; Juno is never queued.

**Unread:** a separate dot (pane top bar and filter list) when something happened in a pane since you last looked —
finished, asked, reported, failed. Cleared by clicking into or scrolling the pane; survives Done → Idle.

## 4. Hierarchy and communication

### 4.1 Links
- Juno and every agent can always message each other (no link needed; can't be closed).
- Two other agents can message each other only while a **link** between them is open. Links are two-way, saved,
  survive restarts, and are removed with either agent.
- Opened/closed by: `/cantalkto<name>` / `/stoptalkingto<name>` in an agent's pane; asking Juno in plain words
  ("let bob and jim talk"); or Juno suggesting one — she must **ask you first** (approval card "Link bob ↔ steve? Why:
  …", **Link / Don't link**, or `/approve` / `/deny`). The server holds an unrequested link until you approve.
- Every change is announced in both agents' panes and Juno's ("winston ↔ bob linked by you" / "by Juno").
- Every agent knows each agent it can talk to by **name, profile and status**: in its preamble (§11), in each link
  announcement, and via `list_agents`. Prompts may depend on linked agents' **profile names** (e.g. the coder, §6.1).

### 4.2 Delegation
1. **Direct:** you type a task in an agent's pane. Juno isn't involved (unless "Tell Juno about direct tasks" is on:
   she gets a one-line note).
2. **Named, through Juno:** "juno, give bob the login bug" (Juno writes the task), or `/assign<name> <task>` in her
   pane (delivered by the app, no Juno tokens; she's told in one line and tracks the result).
3. **Unnamed, through Juno:** "get someone to fix the login bug". Juno **proposes** an agent — approval card with the
   agent, why (profile, status, context use) and the task, **Approve / Edit / Choose another / Cancel** (or `/approve`,
   `/deny`). Held by the server until you decide. Setting **Juno asks before assigning**: *Only when I didn't name
   the agent* (default) / *Always* / *Never*.

Each task either **continues** the agent's conversation or **starts fresh**. Juno prefers fresh when the task is
unrelated or the agent's context is above the **fresh-start threshold** (default 60 %). **Starting fresh always needs
your approval**, whatever the setting: the card says what will be cleared ("Bob's conversation — 65 % context, last
active 10 min ago") with **Start fresh / Continue instead / Cancel**. The old conversation stays in the agent's
Conversations list (§7.9).

### 4.3 Keeping Juno's context small
- When an agent finishes or gets stuck on one of Juno's tasks, Juno gets a **capped report**: status, a summary of at
  most **Report length** characters (default 800), files changed, context use. She can fetch more with
  `get_agent_output`.
- An agent with a **review open** (between `start_review` and `end_review`, any profile) reports only when the review
  is finished (comments posted, declined or cancelled) — not while waiting for pipelines or your approval.
- Reports arriving while Juno works are batched into one digest for when she's next idle.
- Agent-to-agent traffic never enters Juno's context unless she's part of it.
- Juno has her own context meter; `/compact` and `/new` work on her too.

### 4.4 Juno's MCP server
A local MCP server (HTTP on `127.0.0.1`, random token per launch) that every agent is connected to. Its tools are the
only way agents reach each other, and **the server enforces every rule in §4** — a call that breaks one is refused,
whatever a prompt says.

An agent may call a Juno tool only if its **profile's allowed tools** include it (e.g. `mcp__juno__start_review`); the
server refuses it otherwise. Orchestrator-only tools work only for Juno.

| Tool | Allowed by default to | What |
| --- | --- | --- |
| `list_agents` | every profile | Agents the caller may talk to: name, profile, project, status, context use, colour. Juno sees all, plus links. |
| `send_message(to, text)` | every profile | Delivers a message into the target's conversation. Refused without a link. |
| `report(text)` | every profile | Sends a result or question to whoever assigned the current task. |
| `request_folder(reason)` | every profile | Asks you to give the agent a project (§7.7). |
| `assign_task(to, task, fresh)` | orchestrator only | Hands an agent a task (held for approval when required); its result is reported back automatically. |
| `get_agent_output(name, lines)` | orchestrator only | Recent output of an agent. |
| `get_agent_context(name)` | orchestrator only | An agent's context summary (§8.2). |
| `set_link(a, b, open, requested_by_user)` | orchestrator only | Opens/closes a link; an unrequested open is held for your approval. |
| `start_review(target)` / `end_review()` | reviewer | Waits for the PR/MR's pipelines, then opens a review snapshot; `end_review` removes it (§6.3). Any profile allowing them can review. |
| `request_permission(...)` | — (Claude Code itself) | Permission prompts (§7.5). |

**Loop guard:** each agent-to-agent message carries a hop count; a chain stops after **Hop limit** hops (default 10)
without a message from you, and both agents and you are told why. A message from you to either agent resets it.

## 5. Settings (6 tabs)

### 5.1 Keys
- Any number of GitHub and GitLab keys. Each: **label**, **kind**, **host** (default `github.com` / `gitlab.com`),
  **token**, and optional **git identity** (commit author name and email, e.g. a bot account's). A key may be your
  own account's or a separate one (e.g. a bot for coders or reviewers).
- **Storage:** saving stores the token in the OS vault via java-keyring (Windows Credential Manager, macOS Keychain,
  Linux Secret Service) as `juno/key/<id>`; `settings.json` holds only `"token": "keyring:juno/key/<id>"`. Deleting
  a key deletes its vault entry. If no vault is available (e.g. Linux without a keyring daemon, WSL, SSH), the tab
  says so and offers `env:VARIABLE` (Juno stores the variable's name). **Never plain text.**
- Tokens are never shown in full after saving, logged, or put in prompts; they're read only when an agent starts.
- **Test** checks the key against the host's MCP server (and that it can read/comment where relevant).
- The MCP URL comes from the host; **MCP URL override** under Advanced:

| Host | MCP URL |
| --- | --- |
| `github.com` | `https://api.githubcopilot.com/mcp/` |
| GitHub Enterprise Cloud, data residency (`<name>.ghe.com`) | `https://copilot-api.<name>.ghe.com/mcp` (verify) |
| GitHub Enterprise Server (self-hosted) | not supported in this version |
| `gitlab.com` | `https://gitlab.com/api/v4/mcp` |
| Self-managed GitLab | `https://<host>/api/v4/mcp` (GitLab 18.3+, MCP server enabled) |

#### 5.1.1 Which key an agent uses
Keys are set **only on agents** — not on profiles or projects: each agent can have one GitHub key and one GitLab key,
chosen when you create it (§7.10) and changeable in its options menu. The key for the host of the repository being
worked on is used for **everything** the agent does on GitHub/GitLab:
- **MCP tools** — its temporary `mcp.json` gets that key's token;
- **git over the network** (fetch/pull/push) — for that process only, via a per-process credential helper (never
  written into git config or remote URLs); SSH remotes are used over HTTPS so the token applies;
- **commits** — the key's git identity, if set, is the author (set in its own worktree only).

**No key, no GitHub/GitLab** — enforced in the agent's process, not just its prompt: no host MCP servers in its
`mcp.json`; `GIT_ALLOW_PROTOCOL=file` (local repositories only); your credential helpers and SSH agent aren't passed
in; git can't prompt; `gh` and `glab` are disallowed. Local git (commit, branch, diff, log) still works. Anything
needing the host (fetch, push, opening a PR, starting a review) stops at **Awaiting decision 🔑** with the reason
("No GitHub key is set for bob, so I can't push to acme/api — set one in bob's options menu"). Juno's own git
operations for an agent follow the same rule.

#### 5.1.2 First run
A skippable setup: Claude Code path (checked), install the Claude hook, notes folder, a first project, and **adding
your keys** to Keys (your own and any bot accounts', per host), ready to pick when creating agents. Creating an agent
without a key for its project's host shows a warning in its setup form (§7.10).

### 5.2 System settings
- **Install / Uninstall Claude hook**, with status (installed / outdated / not installed). The hook makes "Awaiting
  decision" and "Done" exact. It must: never block or fail Claude Code (always exit 0, quickly, short timeout, even
  with Juno closed); only report for agents Juno started (checks `JUNO_AGENT_ID`); merge into
  `~/.claude/settings.json` without touching other settings or hooks; be idempotent; take a timestamped backup
  before every change.
- **Claude Code path** (default `claude` on PATH), used by profiles without their own.
- **Juno asks before assigning** (§4.2), **Tell Juno about direct tasks**, **Fresh-start threshold** (default 60 %).
- **Report length** (default 800 characters), **Hop limit** (default 10), **Pipeline wait limit** (default 60 min).
- **Max agents working at once**; **idle process timeout** (an idle agent's process is closed; its next message
  resumes it).
- **Notes folder** (§9).
- **Storage:** space per agent and total (worktree, review snapshots, journals and logs, earlier conversations), with
  **Remove worktree** for idle ones (no uncommitted/unpushed work; recreated on next start; flagged after 14 days
  untouched) and **Clean up old data** (applies §7.9 limits now). Never deletes branches, project folders, or
  worktrees with uncommitted or unpushed work.

### 5.3 Projects
- **Name**, **local repository folder** (must exist), optional **remote** (GitHub `owner/repo` or GitLab
  `group/project`), optional **build & test commands** (e.g. `npm ci`, `npm test`). Projects hold no keys.
- Build & test commands are added to the allowed tools of agents that **edit files** in that project (e.g. coders),
  on top of the profile's (even locked) allowed tools.
- Checked on save: folder exists; remote well formed.

### 5.4 Agent profiles

| Setting | What | On built-in profiles |
| --- | --- | --- |
| **Name** | Unique; other prompts refer to profiles by name. | 🔒 |
| **Provider** | Claude Code only for now. | 🔒 |
| **Executable path** | Blank = Claude Code path (§5.2). | editable |
| **Model**, **Effort** | E.g. Opus 5.5 / Sonnet 5.5; low…max or CLI default. | editable |
| **Permission mode** | acceptEdits / auto / manual / dontAsk / plan / bypassPermissions. | 🔒 |
| **System prompt** | Who it is and how it works. | 🔒 |
| **Allowed tools** / **Disallowed tools** | Tool and command rules (`Bash(git:*)`, `mcp__juno__…`, host MCP tools). | 🔒 |
| **Max turns** | Per message, default 150. | editable |
| **Spend limit** | USD per process; blank = none. | editable |
| **Needs a project folder** | Its agents won't start without a project (§7.7). | 🔒 |
| **Edits files** | Its agents get their own worktree (§7.8). | 🔒 |
| **Profile commands** | Extra slash commands: name + prompt template. | built-in ones 🔒 |
| **Skills** | Assigned in the Skills tab. | bundled superpowers assignments 🔒; others editable |

Built-in profiles can't be deleted. **Duplicate** (any profile) makes a fully editable copy with all settings,
skills and commands — the way to change anything locked. Your own profiles are fully editable and deletable.

### 5.5 Skills
- Library of skill packs (`SKILL.md`, folder, or `.zip` / `.skill`), copied into `~/.juno/skills`. Each pack applies
  to the whole orchestration or chosen profiles; individual skills can be switched off globally or **per profile**.
- Agents get their skills as a session-only plugin (`--plugin-dir`); nothing is written into project folders.
- **Bundled: superpowers** (obra/superpowers, MIT). Default assignments: **coder** — all except `using-git-worktrees`
  (Juno manages worktrees); **planner** — only `using-superpowers`, `brainstorming`, `writing-plans`. Bundled packs
  can't be removed, their built-in assignments are locked, and their files are refreshed from Juno's copy on start-up.
  They can be assigned to your own profiles with any skill selection.
- `/activate<skill>` (§8.1): the agent loads and follows that skill from its next message; a pack name activates its
  entry skill (`/activatesuperpowers` → `using-superpowers`).

### 5.6 Commands
Reference of every command (§8) — what, where (any pane / Juno / profile command), example — also via `/help` and the
toolbar **?**. Lists the built-in prompts (§11) read-only.

## 6. Built-in profiles

| Profile | Model / effort | Permission | Needs folder | Edits files | Tools (defaults) |
| --- | --- | --- | --- | --- | --- |
| **orchestrator** | Opus 5.5 / high | default | no | no | Juno tools (all), Read, Grep, Glob |
| **coder** | Sonnet 5.5 / high | acceptEdits | **yes** | **yes** | file tools; `Bash(git:*)` except `git push --force`; project build & test commands; host MCP: open PR/MR, read comments, reply; superpowers sub-agents. **Disallowed:** `gh`, `glab`; approve, merge, close PR/MR; resolve review threads |
| **reviewer** | Sonnet 5.5 / high | default | no | no | Read, Grep, Glob, `Bash(git diff/log/show:*)`, `start_review`/`end_review`; host MCP: read PR/MR, pipelines, comments; post review comments; resolve threads. **Disallowed:** Edit, Write, `gh`, `glab`; approve, request changes, merge, close |
| **planner** | Opus 5.5 / max | default | no | no | Read, Grep, Glob, WebSearch, WebFetch; superpowers `brainstorming`, `writing-plans`. **Disallowed:** Edit, Write |
| **searcher** | Sonnet 5.5 / medium | default | no | no | WebSearch, WebFetch, Read |
| **notes writer** | Sonnet 5.5 / medium | default | no | no | Read, Grep, Glob, `Bash(git diff/log:*)`. **Disallowed:** Edit, Write |

Every profile also gets the "every profile" Juno tools (§4.4). Exact host MCP tool names are confirmed at build time
(§13).

### 6.1 What each system prompt covers
- **orchestrator (Juno):** track who's idle; propose or assign tasks (§4.2) as self-contained tasks; link/unlink when
  asked, and ask before linking on her own; never do the work herself; keep reports to the user short.
- **coder:**
  - Before starting, agree the branch with you (existing, or a new one and its name); ask again if the task changes.
    Works in its own worktree and switches it to that branch.
  - May split large tasks across superpowers sub-agents (shown nested in its pane, §7.3).
  - When done, in order: (1) superpowers' per-task and final whole-branch reviews; (2) run the project's build and
    tests locally and fix failures — if the project has no build & test commands, ask you how to test; (3) push and
    **open an ordinary PR/MR via the host MCP tools** (no remote → stop at the commit). At superpowers' finishing step
    always "push and open a PR" this way: never merge locally, never discard, never `gh`/`glab` (committing, merging
    or pushing onto the base branch is also blocked by git hooks, §7.8).
  - Never approve, merge or close a PR/MR; merging is yours.
  - If linked to an agent whose **profile is `reviewer`** (checked with `list_agents` when done), send it one message
    — "PR #42 opened for branch `feature/login`" — and carry on; nothing comes back. To notify your own reviewer
    profiles, duplicate the coder and add their names to this rule.
  - Acts on review comments only via `/checkforcomments` (§6.2).
- **reviewer:** reviews **PRs/MRs only** (§6.3), any author. Its only output is review comments. Waits for pipelines
  to pass; doesn't build or run tests; reads the snapshot as a diff against the PR/MR's base plus surrounding code;
  never edits. Findings by severity (**blocker / should fix / consider / nitpick**), each with file, line, what's
  wrong, why it matters, suggested fix; plus a verdict. **Always shows you the comments and posts only what you
  approve** (you can drop or edit any). Sends nothing back to the coder. Language-specific skills usually go here.
  Checklist:
  1. **Does what was asked** — matches the task/ticket; nothing missing; no unrelated changes or scope creep.
  2. **Correctness and logic** — inconsistencies, edge cases, off-by-one, wrong assumptions.
  3. **Null and empty handling** — nulls/optionals, empty collections and strings, missing keys, unchecked casts.
  4. **Error handling** — caught at the right level, not swallowed, useful messages, resources released, safe retries.
  5. **Security** — injection (SQL, command, path), input validation, authn/authz, secrets in code or logs, unsafe
     deserialisation, vulnerable dependencies.
  6. **Auditing** — security- and business-relevant actions recorded (who, what, when) where the project expects it.
  7. **Logging** — right level, enough context, no secrets or personal data, not noisy.
  8. **Monitoring and observability** — metrics, traces, health checks where used; failures that would go unnoticed.
  9. **Tests** — new and changed behaviour tested incl. edge cases and failure paths; meaningful assertions; not flaky.
  10. **Duplication and redundancy** — repeated logic, reimplementing what exists, dead code.
  11. **Language and project standards** — idiomatic code, project conventions, naming, formatting, structure,
      accurate comments.
  12. **Concurrency** — thread safety, races, shared mutable state, deadlocks.
  13. **Performance and resources** — needless work in loops, N+1 queries, unbounded growth, leaked
      files/connections/threads.
  14. **Compatibility** — breaking changes to APIs, config, data formats or schemas; migrations included and
      reversible.
  15. **Dependencies** — justified, maintained, licence-compatible, pinned.
  16. **Accessibility** (UI changes) — fully keyboard operable (sensible Tab order, visible focus, no traps, Esc closes
      dialogs); accessible names/labels; text alternatives for images and icons; colour never the only signal;
      sufficient contrast; works when zoomed or text enlarged; motion and auto-play can be paused; form errors say
      what and where.
- **planner:** talks a feature through with you and writes the plan **in its pane only** — including architecture
  and the choice of frameworks, libraries and tools, with reasons and alternatives. It **never writes files**; you
  copy what you need from the pane. When superpowers is activated it uses **only `brainstorming`** (explore one
  question at a time) **and `writing-plans`** (turn the agreed design into a step-by-step plan), never starts
  implementing, and ignores those skills' instructions to save files — the plan goes in the pane.
- **searcher:** searches the web, reads sources, answers with findings and a link for each source.
- **notes writer:** writes human-readable **implementation notes** (what changed, why, how it fits) and **test notes**
  (manual test steps, edge cases) **in its pane only**, from a branch, diff or description; never writes files. To
  read a branch it needs the project (asks via `request_folder`).

### 6.2 Coder: `/checkforcomments`
A built-in profile command. **Nothing changes until you've decided on every comment:**
1. Fetch unresolved review comments on the PR/MR of its branch — people's and agents' (recognised by the marker,
   §6.3).
2. Show the full numbered list, grouped by file.
3. One at a time: the comment, the code, whether it agrees and why, its proposal (change, or reply explaining why
   not). **Wait for your decision:** fix as proposed / fix differently (you say how) / reply only / skip.
4. When all are decided: make the changes, run the tests, commit, push.
5. Show the replies; post only after you approve. Skipped comments get no reply.

The coder never resolves threads (its "resolve thread" tool is disallowed). A reviewer resolves its own threads on
re-review; people's threads are left for you or them.

### 6.3 Reviews (PRs/MRs only)
- **Target:** a PR/MR number or link, or an agent ("review bob's work" = its open PR/MR). `start_review` resolves the
  repository, head commit and base commit through the host. The reviewer needs a key for the host (§5.1.1). Projects
  without a remote can't be reviewed by reviewer agents.
- **Repository not cloned:** if the PR/MR's repository isn't one of your projects, the reviewer stops at **Awaiting
  decision 📁** with the exact `git clone …` command for **you** to run, then add it as a project; it continues
  from there. It never clones itself.
- **Pipelines first.** `start_review` waits for the PR/MR's pipelines (GitHub checks / GitLab pipelines) on the head
  commit. Juno polls the host (every 60 s for 10 minutes, then every 2 minutes, up to the **pipeline wait limit**);
  the reviewer is **Waiting ⏳** meanwhile and is messaged when it ends.
  - **Passed** = every **required** check passed (where the host marks checks required — GitHub branch protection or
    rulesets, GitLab "pipelines must succeed"); otherwise every check. Skipped/neutral count as passed. Failing
    optional checks are listed in the summary but don't block. → review starts.
  - **Needs a person** (GitHub "approve and run", GitLab manual job, pending deployment approval) → at once,
    **Awaiting decision ⚠** ("approve them on GitHub, then press Continue"): **Continue waiting / Cancel**.
  - **Failed** → no review; **Awaiting decision ⚠** with the failed jobs: **Wait for a new commit / Cancel**.
  - **New commits pushed while waiting** → waits again on the new head.
  - **No pipelines configured** → reviews straight away; the summary says nothing checked the build or tests.
  - **Wait limit reached** → **Awaiting decision ⚠**: **Keep waiting / Cancel**.
- **Snapshot:** a temporary **detached worktree** at the head commit (`~/.juno/review/<reviewer>-<pr>-<commit>`); the
  reviewer's process runs in it (restart, conversation kept) and is told the PR/MR, head and base commits and
  pipeline results. Detached, so no branch is checked out there and the coder works and pushes undisturbed. The
  reviewer reads; it can't change the submitted work.
- **Posting:** inline comments on the lines concerned, plus one **summary comment** with the verdict and the reviewed
  commit ("Reviewed at `a1b2c3d`"), all anchored to that commit. Shown to you first; only what you approve is posted.
  If new commits arrived meanwhile, it waits for their pipelines, refreshes the snapshot and re-checks findings first.
  Never approves, requests changes, merges or closes.
- **Marker:** every comment or reply an agent posts starts with "🤖 <agent> (<profile>):", so people see it came from
  an agent and `/checkforcomments` can tell agents' comments from people's.
- **Account:** posts with the reviewer's key (§5.1.1): your own key → appears as you; a bot key → appears as the bot.
- **Re-review** (when you ask again): finds its latest summary comment and the commit recorded there (works after
  `/new` or with another reviewer), waits for pipelines on the new head, reviews only changes since that commit,
  replies on its own earlier threads and **resolves those it confirms fixed** (shown to you first). Never resolves
  threads it didn't start.
- **Clean-up:** `end_review` (or finishing) removes the snapshot; leftovers are removed after 24 hours and shown in
  Storage meanwhile.

### 6.4 Review workflows
Superpowers always reviews a coder's work and the coder runs build and tests locally before opening the PR/MR, so a
reviewer agent is optional. Reviews happen only after pipelines pass. Comments are acted on only when **you** run
`/checkforcomments`. Merging is always yours.

| Workflow | When | What happens |
| --- | --- | --- |
| **Superpowers only** (default) | Coder not linked to a `reviewer` | Superpowers reviews → local build & tests → PR/MR. |
| **Linked reviewer** | Coder linked to a `reviewer` | As above, then the coder messages the reviewer the PR/MR number once; the reviewer waits for pipelines, reviews, shows you its comments, posts what you approve. Nothing goes back to the coder. |
| **Any PR/MR** | You ask a reviewer ("review PR #42" / a link) | Same review, on anyone's PR/MR (asking you to clone the repository first if needed). |
| **Assigned by Juno** | Juno assigns "review bob's PR" | Same review; Juno gets one line once it's finished ("posted 5 comments on #42"). The findings live on the PR/MR. |

### 6.5 Getting a PR/MR's commit locally
All worktrees of a repository (your checkout, agents' worktrees, snapshots) share one object store and refs.
- **Your coders' PRs/MRs:** if the PR/MR's head commit (from the host) exists locally, use it — no network. Otherwise
  (unpushed local commits, or someone else pushed) fetch the PR/MR's head. Always review the **pushed** head.
- **Other PRs/MRs, incl. forks:** fetch `pull/<n>/head` (GitHub) or `merge-requests/<n>/head` (GitLab) from the
  project's remote; also fetch the base commit if missing, so the diff uses the real base.
- Fetched refs go under `refs/juno/review/<reviewer>/<pr>` (never your branches) and are deleted with the snapshot.
- Fetching uses the reviewer's key (§5.1.1); without one it stops at 🔑; if the key can't read the repository, Juno
  says so.
- If another agent's git command holds a lock, retry for a few seconds before reporting.
- Snapshots include submodules and Git LFS files when the repository uses them.

## 7. Board

### 7.1 Layout
- The board is split into panes, one agent each; **no empty panes**. Juno's pane is always shown (movable, not
  closable or hideable).
- **Split** a pane right or down (top bar ⇥ / ⤓); the new half must be filled at once — a new agent (set up in the
  cell, §7.10) or a hidden one. **+ New agent** in the toolbar splits the pane you last used. Splits halve the pane;
  **panes can't be resized**.
- **Move** by dragging a pane's top bar onto another: they swap. The layout is saved.
- **Closing a pane hides the agent** (it keeps running; neighbour takes the space). Deleting is in its settings.
- Minimum pane size: the board scrolls vertically; horizontally only when the window is too narrow.
- **Maximise:** double-click the top bar (or ⤢) to fill the board; again or Esc restores the layout exactly.

### 7.2 Top bar and toolbar
- **Pane top bar:** status (+ reason tag or ⏳), unread dot, name, profile, project and branch (or "no folder"), model
  and effort; resume, stop, maximise, split, **⋯ options menu** (§7.10). The agent's colour outlines the pane.
- **Toolbar:** title, **+ New agent**, board / notes toggle, **inbox**, **usage-limit meter**, **?** commands, Settings.
- **Inbox:** count and list of everything waiting on you — assignment proposals, link suggestions, fresh-start
  approvals, permission requests 🔐, folder requests and repositories to clone 📁, missing keys 🔑, review comments to
  approve, `/checkforcomments` decisions, questions ❓, failed or stuck pipelines ⚠, failures and interruptions ⏸.
  Each item: agent, reason, one-line summary, its answer buttons where it has them, else "go to pane". Items disappear
  when answered anywhere. A quiet **Waiting** section lists agents Waiting ⏳ (agent, PR/MR, minutes).
- **Usage-limit meter:** your Claude plan's limits used, from Claude Code's rate-limit events (`5h 60% · week 68%`,
  reset times on hover; amber > 80 %, red > 95 %). Dollar figures anywhere are labelled **"API-price estimate"**.

### 7.3 Rendered output
Readable blocks, not raw events:
- messages as Markdown;
- thinking: a collapsed "Thinking…" block (text if the provider exposes it, else its length); plans/to-dos as
  checklists that tick off;
- file edits as diffs (file name, red removed, green added); new files as highlighted code;
- commands and output (long output collapsed);
- web searches (query, result titles/links) and fetched pages (address, what was taken);
- messages between agents, with the sender's name and colour;
- **sub-agents** (e.g. superpowers implementers/reviewers): one collapsible block each with its task, tool calls,
  diffs and result (via Claude Code's `--forward-subagent-text`);
- questions and permission requests, highlighted until answered;
- per-turn usage and context, kept quiet.

Everything is **selectable and copyable** (drag-select across blocks; copy buttons on every message and code block —
Markdown / plain code). This is how you take planner and notes-writer output.

### 7.4 Under the terminal
- **"can talk to:"** Juno and linked agents, in their colours (Juno's pane: "everyone").
- **Context meter:** `ctx 46k / 200k · 23%` — amber above the fresh-start threshold, red near the limit — plus tokens
  of the last turn and conversation and their API-price estimate; hover for input/output/cache read/cache write.

### 7.5 Permission prompts
When an agent wants something its permission mode and allowed tools don't cover, Claude Code asks Juno
(`--permission-prompt-tool` → `request_permission`). The agent is **Awaiting decision 🔐**; the request (tool,
command or diff) shows in its pane and the inbox:

| Answer | Effect |
| --- | --- |
| **Allow once** | This call runs. Also `/approve`. |
| **Always allow for this agent** | Saves a per-agent rule, then runs the call. |
| **Always allow for `<profile>`** | Adds the rule to the profile's allowed tools (agents get it from their next start). Not offered for built-in profiles. |
| **Deny** | Doesn't run; the agent is told. Also `/deny`. |

- Before saving, the proposed rule is shown and **editable** (default: program plus first word, e.g.
  `Bash(npm install:*)`; for file tools the folder, e.g. `Edit(src/**)`), with a plain-words description ("any
  `npm install` command").
- Per-agent rules survive restarts; matches are allowed automatically with a quiet line ("✓ allowed by rule …").
  They're listed in the agent's options menu with Remove (§7.10).
- Disallowed tools always win: a matching request is refused without asking.

### 7.6 Filter list
Side list of all agents (colour, unread dot, name, profile, status + reason, context %) with a show/hide checkbox per
agent, text search and status filter (incl. "unread"). Hidden agents keep running and are flagged when they need you;
showing one puts it back where it was. Juno can't be hidden.

### 7.7 Agents without a folder
- A project is optional. Without one, an agent works in a private empty folder under `~/.juno` and can't reach your
  files.
- If its profile **needs a project folder** and it has none, its first message is held, it shows **Awaiting decision
  📁**, and its pane asks you to pick (or create) a project; then the message is sent.
- Any agent can ask for one mid-task with `request_folder` (same picker). Changing an agent's project restarts its
  process, keeping the conversation.

### 7.8 Worktrees
- Agents whose profile **edits files** each get their **own worktree** of the project in `~/.juno/worktrees/<agent>`,
  created on first start, on the branch agreed with you (coder) or by default a new `juno/<agent>` branch off the
  project's current branch.
- Agents that don't edit files read the project's main folder (your checkout) without changing it. Reviewers use
  snapshots (§6.3), never a coder's worktree or your checkout.
- A branch can be checked out in only one place: if an agent needs one checked out elsewhere (another worktree or your
  checkout), Juno says where and the agent asks you rather than forcing it.
- **Protected branches:** in every coder's worktree (any agent whose profile edits files), Juno installs git hooks
  that refuse to **commit, merge or push onto the project's base branches** — the remote's default branch (e.g.
  `main`) and the base branch of any open PR/MR from that worktree. The agent gets a clear refusal ("Juno: committing
  to `main` isn't allowed; work on your own branch and open a PR"). Merging the base branch **into** its own branch
  (to bring it up to date) stays allowed. The hooks apply to that worktree only (via per-worktree `core.hooksPath`)
  and run your repository's own hooks too, so they never affect your checkout or other worktrees.
- Deleting an agent removes its worktree (warning if uncommitted or unpushed work); its branch is kept.
- `/diff`, the top bar and the context summary show the agent's own worktree and branch.

### 7.9 Conversations and data limits
- Starting fresh or `/new` keeps the previous conversation in the agent's **Conversations** list (started/ended, first
  task, size, last summary). Choosing one switches back (resumed by id); the current one takes its place. Not while
  working.
- Each agent keeps its **last 10** earlier conversations, none older than **30 days**; older ones drop off and Juno
  deletes its own journal and log for them. If Claude Code has already deleted a transcript, its entry is greyed out
  ("no longer available").
- The current conversation's journal and log are always kept; logs rotate above 5 MB (one old copy).
- **Deleting an agent** removes everything Juno created for it (worktree with warning, journals, logs, conversation
  list, per-agent rules); Juno **asks** before also deleting its Claude Code transcripts (`~/.claude/projects/`).
- Limits apply on start-up and after each conversation ends.

### 7.10 Adding an agent, and its options menu
**Adding:** a split (or + New agent) shows a setup form inside the new cell — **Name** (unique; used in `/hey<name>`),
**Profile** (fixed once created), **Project** (optional; required to start if the profile needs a folder), **Colour**
(pre-filled), **GitHub/GitLab key** (optional, picked from Keys; blank = no host access; warns if the project's host
has none). **Create** turns the cell
into the terminal; **Cancel** removes the cell.

**Options menu (⋯):** editable **any time, even while working**; changes to how it runs apply from its **next
message** (never interrupting the current step; shown as "applies from the next message"):
- name, colour (at once);
- project (waits until it isn't working; process restarts, conversation kept);
- model, effort (per-agent overrides, same as `/model`, `/effort`);
- GitHub/GitLab key;
- **Allowed for this agent** rules (when/why added, Remove);
- Conversations (§7.9), Usage (as `/usage`);
- **Delete agent** (confirmation; not Juno).

## 8. Commands

**Autocomplete:** typing `/` in a pane's input opens a list of the commands available there (any-pane, Juno-only in
Juno's pane, and the agent's profile commands), filtered as you type, each with a one-line description. After a
command that takes a name (`/hey`, `/cantalkto`, `/stoptalkingto`, `/getcontextfrom`, `/assign`) it suggests agent
names (with colour and status); after `/activate`, the agent's skills; after `/model` / `/effort`, the options. Tab or
Enter accepts, Esc closes. Both forms work: `/heybob …` and `/hey bob …`.

Names in commands ignore case and spaces. A plain message gives work or answers a question; **while an agent works,
plain messages are refused** with a hint to use `/hey<name>` (so a stray keystroke can't interrupt it). Juno's
commands take precedence over same-named Claude Code commands; any other `/command` is **not sent** ("`/init` isn't
supported in Juno — see /help").

### 8.1 Any pane
| Command | What |
| --- | --- |
| `/hey<name> <text>` | Instructions or a question for `<name>` while it works; read when its current step ends. From any pane. |
| `/cantalkto<name>` / `/stoptalkingto<name>` | Open / close a link between this agent and `<name>`. |
| `/getcontext` | This agent's context summary (§8.2); in Juno's pane, Juno's. |
| `/activate<skill>` | Load and follow one of its skills from the next message (pack name → entry skill). Pane shows it's queued; repeat to cancel. Only skills it has; not while working. |
| `/compact` | Summarise the conversation to free context. |
| `/new` | Start a new conversation from the next message. |
| `/stop` / `/resume` | Stop (conversation kept) / continue an interrupted or stopped agent. |
| `/approve` / `/deny` | Answer the pending permission request or proposal. |
| `/model <model>` / `/effort <level>` | Applies from the next message (allowed while working). |
| `/diff` | Uncommitted changes in its folder, as diffs (no tokens). |
| `/usage` | Token, cost and context history. |
| `/copy` | Copy its last reply. |
| `/clear` | Clear the pane (log kept). |
| `/help` | Commands available here. |

### 8.2 Juno's pane only
| Command | What |
| --- | --- |
| `/getcontextfrom<name>` | That agent's context summary. |
| `/assign<name> <task>` | Give a task through Juno without spending her tokens; she tracks the result. |
| `/status` | Every agent: status, project, current task, context %. |
| `/broadcast <text>` | Message every agent. |

**Context summary** is built by the app from the journal — instant, no tokens, nothing added to any conversation:
status, context use, current or last task, files read and changed, links, last summary, conversation age. Shown to you
only; enters Juno's conversation only via `get_agent_context`. To learn what an agent understands, ask it with `/hey`.

### 8.3 Profile commands
Defined on profiles (§5.4), e.g. the coder's `/checkforcomments` (§6.2); expanded from their template and sent as a
message.

## 9. Notepad
- A toggle switches between the board and **note view**: file tree of the **notes folder**, tabs, Markdown editor with
  live preview; create, rename, move, delete notes and folders.
- Agents never get the notes folder; Juno refuses a notes folder inside a project folder or vice versa.
- One agent's terminal can be shown beside your notes — the same agent (same output and input), still on the board.

## 10. Talking to Claude Code (Provider)
- No SDK (none exists for Java): Juno runs `claude -p --input-format stream-json --output-format stream-json
  --verbose …` as a child process, writes messages to stdin as JSON lines and reads events from stdout with Jackson —
  the protocol Anthropic's Agent SDKs use.
- Per launch: `--model`, `--effort`, `--permission-mode`, `--allowedTools`, `--disallowedTools`, `--max-turns`,
  `--max-budget-usd`, `--append-system-prompt-file`, `--forward-subagent-text`, `--strict-mcp-config` +
  `--mcp-config` (Juno's server, plus the host's server for the agent's key), `--plugin-dir`,
  `--permission-prompt-tool`, `--resume <session>`; environment `JUNO_AGENT_ID` and the git settings of §5.1.1.
- **Version check:** Juno declares a **minimum Claude Code version** (the one its flags were verified against during
  development). It runs `claude --version` at start-up, when the Claude Code path or a profile's executable path
  changes, and in first-run setup. Too old or not found → a clear message ("Claude Code 2.1.312 or later is needed;
  you have 2.0.9. Run `claude update`."), and agents using that executable don't start (they show **Awaiting
  decision ⚠** with the same message). Newer versions are allowed; if a launch fails on an unknown flag, the error
  names the flag and the version.
- A `Provider` interface holds everything CLI-specific (command line, output → events, resume, MCP config, skills,
  hook install, version check), so another CLI can be added without touching the rest.
- An agent's output (event list) is separate from any pane showing it (board and note view).

```
dev.juno
├── app        JavaFX entry point, main window, toolbar, board/notes toggle
├── model      profiles, projects, keys, agents, links, settings (plain data, JSON)
├── store      ~/.juno persistence: settings, agents, journals, logs
├── provider   Provider interface + AgentEvent types (Message, Thinking, ToolUse, Diff, Usage, Result…)
│   └── claude ClaudeCodeProvider: command line, stream-json parser, hook installer, MCP config
├── runtime    AgentRuntime (agents, statuses, queueing), AgentProcess (one CLI process), Journal
├── comms      JunoMcpServer, links, delegation/approvals, reports, loop guard, pipeline polling
├── git        agent worktrees, review snapshots, credential helper, /diff
├── prompts    built-in prompt templates (§11)
├── skills     skill library, bundled packs, per-agent plugin folders
└── ui         board, panes and output blocks, setup form/options menu, settings tabs, notepad, filter list
```

## 11. Built-in prompts
Besides profiles' system prompts, all natural-language text Juno sends lives as templates in `prompts/*.md`
(`{{placeholders}}`), listed read-only in Settings → Commands:
1. **Agent preamble** (before every system prompt): its name, profile, project and folder (or none); who it can talk
   to (name and profile) and how; that links change (announcements; `list_agents` is current); how to report and ask
   questions; that `[Message from …]` and `[Extra info from the user]` blocks may arrive mid-task.
2. **Juno MCP tool and parameter descriptions** (§4.4).
3. **Tool results**, esp. refusals ("You are not linked to bob. Ask Juno to link you, or ask the user.").
4. **Wrappers** for what Juno injects: agent messages, tasks, `/hey` info, reports and digests, approval outcomes, link
   announcements, loop-guard notice, pipeline-wait outcomes.
5. **Resume recap** after an interruption.
6. **Profile command templates** (part of profiles).

## 12. Non-negotiables (lessons from v1)
- GitHub/GitLab access only through the agent's own key (§5.1.1): temporary `mcp.json` (deleted when the
  process ends) and `--strict-mcp-config`; no key → no host access at all. Tokens never in prompts or logs.
- Session id, status, links and an event journal are saved as they happen. After a crash or shutdown an agent shows
  **Awaiting decision ⏸**, and Resume continues the same conversation with a recap of what it had done.
- Working/idle follows the agent's output events, not message counts (a message sent mid-turn can be merged into the
  current turn).
- On Windows, launch npm-installed CLIs via `cmd.exe /c`.
- Token usage and context size recorded per turn for every agent.

## 13. Open items
**To write (Milestone 0):** the built-in profiles' system prompts (§6.1) and the prompt templates (§11).

**To verify before building the GitHub/GitLab parts** — what the hosts' own MCP servers support:
- inline review comments anchored to a commit, and a summary comment;
- replying to threads and resolving them (exact tool names, so the coder can be denied resolving);
- pipeline/check status per commit, which checks are required, and "waiting for approval" states;
- opening a PR/MR; exact names of approve / merge / close tools (to disallow them);
- GitLab: whether its MCP server accepts a personal access token (not only OAuth).

Where something is missing, Juno calls the host's REST API with the same key instead (pipeline polling may use REST
anyway).
