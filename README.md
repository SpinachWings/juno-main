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

## Using it

1. **Settings → Projects**: add each codebase with its local directory, a colour (cards linked to the
   project are tinted with it), and optionally its GitLab project (`group/project`) and GitHub repo
   (`owner/repo`).
2. **Settings → Agent**: choose the Claude Code executable, the model, the permission mode, allowed tools,
   max turns, and an optional API key or base URL. Leave the key blank to use your existing `claude` login.
3. **Settings → Integrations**: set the GitLab URL and GitHub API URL, the tokens, and the MCP jar paths.
   If the three repos sit side by side, the jar paths are filled in for you. Use **Test MCP server** to
   check each one starts.
4. Use **+ New ticket** or **Import issue**. On a card, **Details** opens the ticket menu:
   - edit the title and full description
   - link the ticket to a project (filled in automatically on import when the repo/project matches)
   - upload ticket info to GitLab or GitHub (creates the issue, or updates the linked one)
   - import ticket info from GitLab or GitHub (pick from open issues or enter a number)
5. **Start** runs `claude -p --output-format stream-json …` in the project directory, with the ticket as
   the prompt. **Log** shows live agent activity.

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
- `settings.json`: projects, agent and integration settings. **Tokens and API keys entered in Settings
  are stored here in plain text.** Prefer leaving them blank and setting `GITLAB_TOKEN`, `GITHUB_TOKEN` or
  `ANTHROPIC_API_KEY` in your environment.
- `logs/<ticket>.log`: agent activity
- `agent-mcp.json`: generated MCP config for agents, when enabled

## Layout

```
dev.juno.main
├── JunoApp              JavaFX entry point
├── model                Ticket, Project, settings (plain Jackson-mapped classes)
├── store                JSON persistence in ~/.juno
├── mcp                  stdio MCP client
├── integration          GitLab/GitHub via the MCP servers
├── agent                Claude Code process runner, stream-json parser, prompts
├── service              BoardService: orchestration, thread-safe, UI-agnostic
└── ui                   Board, cards, dialogs
```
