# Juno

A JavaFX orchestrator for Claude Code agents. Tickets move across a six-column board:

| Column | Meaning |
| --- | --- |
| **Pending** | Created or imported, not started. **Start** hands the ticket to an agent. |
| **Processing** | An agent is working on the ticket's branch. The ticket stays here until its branch is pushed and its pull request opened. |
| **Awaiting decision** | The agent asked a question, failed, or Juno couldn't push / open the PR. Answer on the board (**Respond**) or in the ticket's terminal; **Retry** re-runs a failed push. |
| **Review** | PR/MR open (link on the card) and an **AI reviews run** counter. **Run AI review** starts an AI review; **Resolve conflicts** brings the branch up to date with its base branch; **Move to Done** asks for confirmation first. Juno never merges: a human merges the PR in GitLab/GitHub. |
| **In AI review** | An AI review agent is addressing the PR's comments in its own terminal. When it finishes, its fixes are pushed and the ticket returns to Review. |
| **Resolving conflicts** | An agent is resolving merge conflicts between the branch and its updated base branch, in its own terminal. When it finishes, Juno pushes the merge and the ticket returns to Review. |
| **Done** | Finished. **Delete** removes it from Juno with everything its agents left on this computer: working copy, logs, run records, terminal scripts and Claude Code's session history (its branch, PR and issue stay). To take a ticket out of Done, right-click it → Move to. |

Tickets can be imported from or uploaded to GitLab and GitHub. Juno does this through the
[`juno-gitlab-mcp`](../juno-gitlab-mcp) and [`juno-github-mcp`](../juno-github-mcp) MCP servers, which
listen on localhost ports (GitHub **9950**, GitLab **9951** by default). Juno either starts their jars itself or
connects to servers you started from a terminal.

## Requirements

- JDK 27
- Maven 3.9+
- [Claude Code](https://docs.claude.com/en/docs/claude-code) installed and logged in (`claude` on your PATH)
- The `juno-gitlab-mcp` and `juno-github-mcp` repos next to this one. They need no setup: if a server's jar
  hasn't been built yet, Juno builds it (`mvn package`) the first time it's needed.

## Run

```bash
mvn javafx:run
```

All GitLab/GitHub configuration (URLs and tokens) happens in Juno, per organisation, under
**Settings → Organisations & projects**. The MCP servers store no tokens; their only setting is their port.
Juno sends the ticket's organisation's token and API URL in the headers of every call
(`Authorization: Bearer …`, `X-Api-Url`), so one server serves every organisation without mixing credentials.
Nothing is taken from a `GITHUB_TOKEN` / `GITLAB_TOKEN` in Juno's own environment.

### MCP servers: jar or port

**Settings → General** has, for each server, a port and one of two modes:
- **Juno starts the jar** (default): when a server is first needed, Juno checks the port. If the server is
  already running there, Juno uses it. Otherwise it starts `java -jar <jar> --port <port>`, building the jar
  first if it's missing, and stops it again when Juno closes.
- **Already running on localhost**: you start the server yourself (`./run.sh` / `run.cmd` in its folder, or
  `java -jar juno-github-mcp.jar --port 9950`) and Juno only connects to the port.

Juno checks that what answers on the port really is that server. If nothing is listening, or another
program has the port, the ticket's Start reports it.

`mvn javafx:jlink` builds a self-contained runtime image with a `juno` launcher in `target/juno`.

## Organisations, projects and agents

```
Organisation   GitLab / GitHub integration (URL + token), default agent
 └─ Project    local directory, colour, repository (on GitHub or GitLab), optional agent, extra allowed tools
     └─ Ticket optional agent
```

- **Organisations** are credential boundaries. Each call to an MCP server carries only that organisation's
  token, and an agent's MCP config only ever contains its ticket's organisation's token.
- **Agent profiles** (Claude instance, model, permissions) are defined once under **Settings → Agents**.
  They can be assigned to an organisation, a project or a ticket. The most specific one wins:
  **ticket → project → organisation**.
- **Repo matching is per organisation.** Importing `acme/api` in one organisation never links to another
  organisation's project with the same repo name.
- The board has an **Organisation** filter and a **Project** filter. With *All organisations*, the project
  list shows every project from every organisation. Cards show `Organisation · Project`, the title, the agent that
  will run, and the base branch and working branch. Tickets have no numbers: they are always shown by title. Each card is tinted with its project's colour.

Settings written by earlier versions are migrated automatically on first start:
- The single GitLab/GitHub setup becomes a "Default" organisation.
- The single agent becomes "Default agent", assigned to that organisation.
- Existing projects move into the Default organisation.

The original file is kept as `settings.pre-orgs.json`. A project that had both a GitHub repo and a GitLab
project keeps the one its organisation has enabled (GitHub if both are), Juno says so on start-up, and the
previous file is kept as `settings.pre-single-host.json`.

## Using it

1. **Settings → Organisations & projects**: add an organisation, enable GitLab and/or GitHub for it with a
   URL and token, and pick its default agent. Add projects under it: directory, colour (cards are tinted
   with it), its repository (a project is hosted on GitHub **or** GitLab, never both: pick the host, then
   `owner/repo` or `group/project`), and optionally their own agent and extra allowed tools (e.g. the
   project's build/test commands). Save checks the repository format and that its host is enabled for the
   organisation.
2. **Settings → Agents**: create agent profiles. Each sets the Claude Code program, model, permission
   mode, allowed tools, max turns (default 150), time limit (default 60 minutes), and an optional API key or
   server URL. Leave the key blank to use your existing `claude` login. A run that hits its turn or time
   limit stops in Awaiting decision; replying continues it. Agents never get GitHub/GitLab access themselves:
   Juno does the pushing, pull requests and comments.
3. **Settings → General**: for each MCP server, its port and whether Juno starts the jar or connects to a
   server you run (see above); the jar paths (leave blank to use the servers next to juno-main, which are
   built automatically if missing); the Java runtime for them; and the overall limit on concurrent agents.
4. Use **+ New ticket** or **Import**. Import asks for the organisation and project first, because
   their credentials are used. On a card, **Details** opens the ticket menu:
   - edit the title and full description
   - set the organisation, project and agent override
   - upload ticket info to the project's host (creates the issue, or updates the linked one)
   - import ticket info from the project's host

   A ticket links to at most one issue, on its project's host. Moving a ticket to a project in another
   organisation removes the link, after a warning, since it belongs to the old organisation's credentials.
5. **Start** needs a project: tickets without one can be saved but not run. Before anything is touched, Juno
   checks that the ticket has a title, base branch and agent; that its project has an existing git folder
   and a repository; that the organisation has that host enabled with a token; and, on GitHub/GitLab, that
   the repository exists (and the token can see it) and that the base branch exists there. Every problem
   found is listed in one message, and the ticket stays in Pending. It then runs the resolved agent with `claude -p --output-format stream-json …` in the project
   directory, using the ticket as the prompt. **Log** shows live activity.

### Ticket fields

| Field | Synced with GitLab/GitHub? | Used for |
| --- | --- | --- |
| Title | yes | Issue title; PR title; branch name |
| Description | yes, both ways | Upload replaces the issue description; import replaces the ticket's description. Also part of the agent prompt. |
| Initial instructions | no | Extra guidance given to the agent when it first picks up the ticket |
| Base branch | no | Branch the work starts from and the pull request targets (default `main`) |
| Branch name | no | Branch the agent works on (blank = `juno/<title>`, with `-2`, `-3`… if another open ticket already uses it) |

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
   - The PR goes to the project's repository, on its host (GitHub or GitLab). Its title is the ticket's
     title.
3. Once pushed and the PR is open, the ticket moves to **AI review** with the PR link on its card. If
   something failed (no commits, push rejected, repo not configured), it moves to **Awaiting decision**
   with the reason. **Retry** tries again and never opens a duplicate PR; replying there resumes the agent
   instead.

Tips:
- Add `Bash(git add:*) Bash(git commit:*) Bash(git status) Bash(git diff:*)` to the agent's allowed tools,
  so it can commit with meaningful messages. Otherwise Juno makes a single commit at the end.
- Once the work is pushed and the PR is opened (or an AI review's fixes are pushed and its summary posted),
  Juno removes the ticket's worktree, so you can `git checkout` its branch in your own folder. The branch
  itself is kept, and the next AI review recreates the worktree from it. Switch your folder off the branch
  before running an AI review: git only allows a branch in one place at a time, and Juno says so if it's
  still checked out. If the delivery fails, the worktree stays for Retry.

### Agent terminals

When an agent starts, Juno opens a terminal window titled `Juno - <ticket title> [<ticket id>]` with a live
agent console:
- it streams everything the agent does (the same as **Log**, including history)
- when the agent asks for a decision, it shows the question and lets you type the answer. Finish with an
  empty line.

You can answer either there or on the board; whichever comes first wins and the other updates itself. The
**Terminal** button on a card brings the ticket's open terminal to the front (restoring it if minimised); it
only opens a new console if none is open, so a ticket never gets a duplicate window. Juno finds the window by
the `[<ticket id>]` tag in its title, which stays the same when you rename the ticket.

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
4. Commits and pushes the fixes to the same PR, then posts the agent's summary as one comment on the PR
   ("AI review N"). The comment is tagged so later reviews ignore it. The summary is also on the card and in
   the log.

Then the ticket returns to Review with its counter incremented. The review agent gets its own terminal
(`Juno - <ticket title> (AI review)`), and its questions also come through Awaiting decision.

### Resolving merge conflicts

When two tickets are worked on in parallel and one is merged first, the other's PR can conflict with the
updated base branch. **Resolve conflicts** (Review column) handles that:
1. Juno brings the ticket's branch up to date, fetches `origin/<base>` and merges it into the branch. If it
   merges cleanly, or the branch already contains it, nothing changes and Juno says so.
2. Otherwise the ticket moves to **Resolving conflicts**, and an agent gets the conflicting files plus the
   requirements of **both** sides: this ticket (description, instructions, design doc, what was implemented)
   and the work already merged that it conflicts with. Juno finds that work from the base branch's commits
   that touched the conflicting files, matching them to Juno tickets by PR number or branch name, and lists
   those commits too. The agent's job is to keep both sets of requirements working, and to explain any
   trade-off.
3. Juno checks that no conflict markers are left, commits the merge (a normal merge commit, no rebase or
   force-push), confirms the branch now contains the latest base, pushes it to the same PR and moves the
   ticket back to Review, with the agent's summary on the card.

Agents can't run `git merge` themselves. Juno starts and completes the merge. Stopping the agent, or moving
the ticket back to Review from Awaiting decision, abandons the unfinished merge and leaves the branch as it
was.

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

### Design docs

For more complex features, tick **Generate design doc** in the ticket's Details. **Start** then begins with a
design conversation instead of code:
1. The agent reads the codebase (without changing it) and asks its questions, which you answer in the terminal
   or with **Respond**. It then drafts the doc.
2. The draft appears in the terminal and under **Review design** on the card. There you can:
   - **approve** it (`/approve` in the terminal, or the button), which starts implementation with the
     approved design in the agent's prompt
   - reply with changes, and the agent revises it
   - **export** it as `.txt` (`/export` in the terminal, or from the dialog or Details)
3. Exports go to the **design doc folder** (Settings → General; blank means `Documents/Juno/design-docs`) as `<title>-design.txt`.

To use your own design doc instead, paste it into the Design doc box in Details. It is used as-is.

### Skills

Settings → **Skills** manages skill packs, i.e. folders of Claude skills (`<skill>/SKILL.md`):
- **Uploading.** Add a `SKILL.md`/`.md` file, a folder, or a `.zip`/`.skill` archive. It is copied into
  `~/.juno/skills/`.
- **Scope.** Each pack applies **everywhere** (the whole orchestration), to one **organisation**, or to one
  **project**. Individual skills inside a pack can be switched off.
- **At run time.** Before each run, the skills that apply to the ticket are copied into its worktree's
  `.claude/skills/juno-*` folders. They are excluded from git there, via the repository's
  `.git/info/exclude`, so they are never committed. "Skill" is added to the agent's allowed tools.

Bundled with Juno and added at the orchestration level on first start:
- **superpowers** ([obra/superpowers](https://github.com/obra/superpowers), MIT; unmodified copy, source
  commit in `bundled-skills/superpowers/SOURCE.txt`). `finishing-a-development-branch` and
  `using-git-worktrees` start **switched off** because they conflict with Juno: Juno creates the worktree,
  pushes and opens the PR, and nothing is merged without a human.

Juno's rules always take precedence over any skill.

### Never merging

Pull/merge requests are only ever merged by a human, in GitLab/GitHub. This is enforced in four places:
- **MCP servers.** They only reach an allowlist of endpoints (copy/paste ticket info, open a PR/MR, read
  review comments, post Juno's AI review and conflict resolution summaries on a PR/MR, and a read-only repository/branch check). Merge,
  approve, auto-merge and rebase endpoints are blocked outright.
- **Agents have no GitHub/GitLab access.** Only Juno itself talks to the MCP servers; agents are never given
  their tools or your tokens. Juno pushes branches itself, with your own git login.
- **Agent command line.** Every agent is started with `--disallowedTools` covering `git push`,
  `git merge`/`rebase`, `gh pr merge`/`review`/`close`, `glab mr merge`/`approve`/`close` and the `gh`/`glab`
  API commands.
- **Prompts.** They tell agents explicitly never to merge.

Don't add `gh`, `glab` or `curl` to an agent's allowed tools.

Token scopes can't stand in for this: on GitHub, the permissions needed to open a PR also allow merging it.
For a server-side backstop, protect your base branch (require a pull request with at least one approval).

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
such as `Bash(mvn test:*) Bash(git status)` under **Allowed tools**. Every agent is told which commands it
may run, so it can, for example, install missing dependencies before building.

## Data

Everything is stored in `~/.juno` (override with the `JUNO_HOME` environment variable):

- `board.json`: tickets
- `settings.json`: organisations, projects, agent profiles and integration settings. **Tokens and API
  keys typed into Settings are stored here in plain text.** Prefer `env:VARIABLE` references (for example
  `env:ACME_GITLAB_TOKEN`), which are read from Juno's environment when needed.
- `logs/<ticket>.log`: agent activity. Over 5 MB it moves to `<ticket>.1.log` (replacing the previous one).
- `worktrees/<ticket>/`: the git worktree each ticket's agent works in. Removed once its work is delivered,
  recreated by the next run.
- `runs/<ticket>/`: the journal and prompt of the ticket's 10 most recent agent runs, used to resume
  interrupted work

- `consoles/`: the scripts that open each ticket's terminal; `console.token` secures the console API

Claude Code also keeps its own history of each agent's session, in `~/.claude/projects/` (one folder per
worktree, e.g. `C--Users-you--juno-worktrees-<ticket>`).

Deleting a ticket removes its worktree (with any uncommitted changes), logs, runs, terminal scripts and that
Claude Code history, and closes its terminals; its branch is kept. On start-up Juno also removes these files for
tickets that no longer exist (worktrees only if they have no uncommitted changes), and agent connection files
left by earlier versions.

## Layout

```
dev.juno.main
├── JunoApp              JavaFX entry point
├── model                Organisation, Project, Ticket, AgentProfile, settings + agent resolution
├── store                JSON persistence in ~/.juno
├── mcp                  MCP client (HTTP, localhost)
├── integration          GitLab/GitHub via the MCP servers (started by Juno or already running)
├── agent                Claude Code process runner, stream-json parser, prompts
├── git                  worktree/branch preparation, commit, push
├── console              agent terminals: local console API, terminal launcher, console client
├── skills               skill library: uploads, bundled packs, per-run copies
├── service              BoardService (entry point; tickets and settings, as snapshots) and its parts:
│                        Preflight, AgentRuns, Delivery, TrackerSync, Terminals, Housekeeping
└── ui                   Board, cards, dialogs
```

## Look and feel

Juno uses a dark theme (`src/main/resources/dev/juno/main/ui/juno.css`) with light-blue and magenta accents
and square corners. It bundles two fonts under the SIL Open Font License; their licence texts are next to
the font files in `ui/fonts/`:
- [Share Tech Mono](https://fonts.google.com/specimen/Share+Tech+Mono): the interface font
- [VT323](https://fonts.google.com/specimen/VT323): the title, column headers and section headings

The palette is defined once at the top of the stylesheet.

