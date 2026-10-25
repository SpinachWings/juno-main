# Juno

A JavaFX orchestrator for Claude Code agents. Tickets move across a six-column board:

| Column | Meaning |
| --- | --- |
| **Pending** | Created or imported, not started. **Start** hands the ticket to an agent. |
| **Processing** | An agent is working on the ticket's branch. The ticket stays here until its branch is pushed and its pull request opened. |
| **Awaiting decision** | The agent asked a question, failed, or Juno couldn't push / open the PR. Answer on the board (**Respond**) or in the ticket's terminal; **Retry** re-runs a failed push. |
| **Review** | PR/MR open (link on the card) and an **AI reviews run** counter. **Run AI review** starts an AI review; **Move to Done** asks for confirmation first. |
| **In AI review** | An AI review agent is addressing the PR's comments in its own terminal. When it finishes, its fixes are pushed and the ticket returns to Review. |
| **Done** | Finished. |

Tickets can be imported from or uploaded to GitLab and GitHub. Juno does this through the
[`juno-gitlab-mcp`](../juno-gitlab-mcp) and [`juno-github-mcp`](../juno-github-mcp) MCP servers, which it
starts as child processes.

## Requirements

- JDK 27
- Maven 3.9+
- [Claude Code](https://docs.claude.com/en/docs/claude-code) installed and logged in (`claude` on your PATH)
- The two MCP servers built (`mvn package` in each), for GitLab/GitHub sync

## Run

```bash
# build the MCP servers once (from the juno folder)
cd ../juno-gitlab-mcp && mvn package
cd ../juno-github-mcp && mvn package

# run Juno
cd ../juno-main
mvn javafx:run
```

`mvn javafx:jlink` builds a self-contained runtime image with a `juno` launcher in `target/juno`.

## Organisations, projects and agents

```
Organisation   GitLab / GitHub integration (URL + token), default agent
 └─ Project    local directory, colour, GitLab project / GitHub repo, optional agent, extra allowed tools
     └─ Ticket optional agent
```

- **Organisations** are credential boundaries. Each one runs its own GitLab/GitHub MCP server process with
  only its own token. An agent only ever gets its ticket's organisation's servers.
- **Agent profiles** (Claude instance, model, permissions) are defined once under **Settings → Agents**.
  They can be assigned to an organisation, a project or a ticket. The most specific one wins:
  **ticket → project → organisation**.
- **Repo matching is per organisation.** Importing `acme/api` in one organisation never links to another
  organisation's project with the same repo name.
- The board has an **Organisation** filter and a **Project** filter. With *All organisations*, the project
  list shows every project from every organisation. Cards show `ID · Organisation · Project`, the title,
  and the agent that will run. Each card is tinted with its project's colour.

Settings written by earlier versions are migrated automatically on first start:
- The single GitLab/GitHub setup becomes a "Default" organisation.
- The single agent becomes "Default agent", assigned to that organisation.
- Existing projects move into the Default organisation.

The original file is kept as `settings.pre-orgs.json`.

## Using it

1. **Settings → Organisations & projects**: add an organisation, enable GitLab and/or GitHub for it with a
   URL and token, and pick its default agent. Add projects under it: directory, colour (cards are tinted
   with it), GitLab project / GitHub repo, and optionally their own agent and extra allowed tools (e.g. the
   project's build/test commands).
2. **Settings → Agents**: create agent profiles. Each sets the Claude Code program, model, permission
   mode, allowed tools, max turns, and an optional API key or server URL. Leave the key blank to use your
   existing `claude` login.
3. **Settings → General**: the MCP jar paths (filled in automatically when the repos sit side by side),
   the Java runtime for them, and the overall limit on concurrent agents.
4. Use **+ New ticket** or **Import issue**. Import asks for the organisation and project first, because
   their credentials are used. On a card, **Details** opens the ticket menu:
   - edit the title and full description
   - set the organisation, project and agent override
   - upload ticket info to GitLab or GitHub (creates the issue, or updates the linked one)
   - import ticket info from GitLab or GitHub

   Moving a ticket to a project in another organisation removes its issue links, after a warning, since
   they belong to the old organisation's credentials.
5. **Start** runs the resolved agent with `claude -p --output-format stream-json …` in the project
   directory, using the ticket as the prompt. **Log** shows live activity.

### Ticket fields

| Field | Synced with GitLab/GitHub? | Used for |
| --- | --- | --- |
| Title | yes | Issue title; PR title (`JUNO-12: <title>`) |
| Description | yes, both ways | Upload replaces the issue description; import replaces the ticket's description. Also part of the agent prompt. |
| Initial instructions | no | Extra guidance given to the agent when it first picks up the ticket |
| Base branch | no | Branch the work starts from and the pull request targets (default `main`) |
| Branch name | no | Branch the agent works on (blank = `juno/<ticket>-<title>`) |

Base branch and branch name are fixed once work has started.

### Branches and pull requests

1. **Start**:
   - Juno fetches `origin/<base>` and creates the ticket's branch from it.
   - The branch gets its own git worktree, in `~/.juno/worktrees/<ticket>`, so several agents can work on
     one repository at once and your own checkout is never touched.
   - If the branch already exists, it is checked out instead.
   - The agent runs in that worktree. Its prompt tells it to commit to the branch and not to push or switch
     branches.
2. **When the agent finishes** (the ticket stays in Working meanwhile):
   - Juno commits anything it left uncommitted and checks the branch has commits beyond the base.
   - It runs `git push -u origin <branch>` with your own git credentials, and never prompts for them.
   - It then opens a pull request (GitHub) or merge request (GitLab) into the base branch through the
     organisation's MCP server. The agent's summary becomes the description, plus `Closes #n` when the
     linked issue is in the same repository.
   - The PR goes to the tracker the ticket is linked to if possible, otherwise GitHub, then GitLab, using
     the project's configured repo.
3. Once pushed and the PR is open, the ticket moves to **AI review** with the PR link on its card. If
   something failed (no commits, push rejected, repo not configured), it moves to **Awaiting decision**
   with the reason. **Retry** tries again and never opens a duplicate PR; replying there resumes the agent
   instead.

Tips:
- Add `Bash(git add:*) Bash(git commit:*) Bash(git status) Bash(git diff:*)` to the agent's allowed tools,
  so it can commit with meaningful messages. Otherwise Juno makes a single commit at the end.
- Worktrees stay in place after the PR is opened, so a resumed session or a retry reuses them. Remove one
  with `git worktree remove <path>` once the PR is merged.

### Agent terminals

When an agent starts, Juno opens a terminal window titled `Juno <ticket>` with a live agent console:
- it streams everything the agent does (the same as **Log**, including history)
- when the agent asks for a decision, it shows the question and lets you type the answer. Finish with an
  empty line.

You can answer either there or on the board; whichever comes first wins and the other updates itself. The
**Terminal** button on a card brings that window to the front, or opens a fresh console if you closed it.

How it works:
- Agents still run headless under Juno's control. That is what lets both the board and the terminal answer
  questions and lets Juno detect when the work is done.
- The console talks to Juno over a local API bound to `127.0.0.1` and protected by a random token
  (`~/.juno/console.token`).
- Windows uses Windows Terminal if installed, otherwise a console window. macOS uses Terminal.app. Linux uses
  the first terminal emulator found. **Settings → General** can turn terminals off or set a custom command
  (`{title}`, `{script}`).

### AI review

**Run AI review** (Review column). With no new comments on the PR it completes immediately: the counter goes
up and the ticket stays in Review. Otherwise the ticket moves to **In AI review** while it:
1. Reads the PR/MR's feedback: conversation comments, review summaries and inline comments with file, line
   and diff context. It also reads comments on the linked issues made since the PR was opened. Resolved
   GitLab threads and Juno's own comments are ignored.
2. Fetches the branch and fast-forwards it if someone else pushed to it.
3. Runs the ticket's agent (ticket → project → organisation) with that feedback, marking what is new since
   the last AI review.
4. Commits, pushes the fixes to the same PR, and posts the agent's summary as a PR comment.

Then the ticket returns to Review with its counter incremented. The review agent gets its own terminal
(`Juno <ticket> AI review`), and its questions also come through Awaiting decision.

### Interruptions and Resume

Agents run under Juno. If Juno closes, crashes or the computer restarts mid-run, the agent stops, but nothing
is lost:
- **Journal.** Every run writes `~/.juno/runs/<ticket>/<time>-<work|review>.jsonl`: every message, tool call
  and result as it happens. The prompt it was given is saved next to it in `.prompt.md`.
- **Session id.** The agent's Claude session id is saved the moment the run starts.
- **Work on disk.** Commits and uncommitted changes stay in the ticket's worktree.

On the next start, such tickets stay in **Processing** or **In AI review**, greyed out, with a **Resume**
button. Resume:
1. stops any leftover agent process from the interrupted run (only if it is provably that process);
2. continues the same Claude session with a recap of the journal (its last steps, marked where it was cut
   off) and the branch's current commits and uncommitted files;
3. if that session can't be restored, starts a new session automatically with the original prompt plus the
   same recap;
4. if only the final push or PR step was interrupted, simply retries it.

Closing a ticket's terminal window does **not** interrupt the agent; **Terminal** reopens the console. To
abandon an interrupted run, move the ticket to another column.

### How agents ask for decisions

The prompt tells the agent to end its final reply with

```
JUNO_DECISION_REQUIRED: <question>
```

when it needs a human. Juno moves the ticket to **Awaiting decision**. **Respond** sends your answer to the
same Claude session (`--resume`). Failed runs, and runs interrupted by closing Juno, land in the same column
so you can retry them.

### Permissions

Headless Claude Code refuses any tool that needs approval unless the permission mode or the allowed-tools
list permits it. The default `acceptEdits` lets agents edit files. To let them build and test, add rules
such as `Bash(mvn test:*) Bash(git status)` under **Allowed tools**. If you enable *Give agents the
GitLab/GitHub MCP tools*, also allow `mcp__juno-gitlab` and/or `mcp__juno-github`.

## Data

Everything is stored in `~/.juno` (override with the `JUNO_HOME` environment variable):

- `board.json`: tickets
- `settings.json`: organisations, projects, agent profiles and integration settings. **Tokens and API
  keys typed into Settings are stored here in plain text.** Prefer `env:VARIABLE` references (for example
  `env:ACME_GITLAB_TOKEN`), which are read from Juno's environment when needed.
- `logs/<ticket>.log`: agent activity
- `agent-mcp/<ticket>.json`: per-run MCP config for agents (only that ticket's organisation's servers)
- `worktrees/<ticket>/`: the git worktree each ticket's agent works in
- `runs/<ticket>/`: the journal and prompt of every agent run, used to resume interrupted work
- `consoles/`: the scripts that open each ticket's terminal; `console.token` secures the console API

## Layout

```
dev.juno.main
├── JunoApp              JavaFX entry point
├── model                Organisation, Project, Ticket, AgentProfile, settings + agent resolution
├── store                JSON persistence in ~/.juno
├── mcp                  stdio MCP client
├── integration          GitLab/GitHub via per-organisation MCP server processes
├── agent                Claude Code process runner, stream-json parser, prompts
├── git                  worktree/branch preparation, commit, push
├── console              agent terminals: local console API, terminal launcher, console client
├── service              BoardService: orchestration, thread-safe, UI-agnostic
└── ui                   Board, cards, dialogs
```

## Look and feel

Juno uses a dark theme (`src/main/resources/dev/juno/main/ui/juno.css`) with light-blue and magenta accents
and square corners. It bundles two fonts under the SIL Open Font License; their licence texts are next to
the font files in `ui/fonts/`:
- [Share Tech Mono](https://fonts.google.com/specimen/Share+Tech+Mono): the interface font
- [VT323](https://fonts.google.com/specimen/VT323): the title, column headers and section headings

The palette is defined once at the top of the stylesheet.

