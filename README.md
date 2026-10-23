# Juno

A JavaFX orchestrator for Claude Code agents. Tickets move across a six-column board:

| Column | Meaning |
| --- | --- |
| **Pending** | Created or imported, not started. **Start** hands the ticket to an agent. |
| **Working** | A Claude Code agent is running in the ticket's project directory. |
| **Awaiting decision** | The agent asked a question, failed, or was interrupted. **Respond** resumes its session. |
| **AI review** | The agent finished; its summary is shown on the card. |
| **Human review** | *(not automated yet; move tickets here manually)* |
| **Done** | *(not automated yet)* |

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

## Layout

```
dev.juno.main
├── JunoApp              JavaFX entry point
├── model                Organisation, Project, Ticket, AgentProfile, settings + agent resolution
├── store                JSON persistence in ~/.juno
├── mcp                  stdio MCP client
├── integration          GitLab/GitHub via per-organisation MCP server processes
├── agent                Claude Code process runner, stream-json parser, prompts
├── service              BoardService: orchestration, thread-safe, UI-agnostic
└── ui                   Board, cards, dialogs
```
