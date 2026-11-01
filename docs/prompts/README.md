# Built-in prompts

All natural-language text Juno sends to agents (design §11). Shown read-only in Settings → Commands.

| Path | What |
| --- | --- |
| `profiles/<profile>.md` | System prompt of each built-in profile (§6.1). Appended after `preamble.md`. |
| `commands/<profile>-<command>.md` | Profile command templates (§8.3), e.g. the coder's `/checkforcomments`. |
| `preamble.md` | Agent preamble, before every system prompt. |
| `tools/<tool>.md` | Juno MCP tool description (text under the title) and parameter descriptions (`## Parameters`). |
| `results/<tool>.md` | Tool results and refusals; one `## <key>` section per message. `common.md` holds shared ones. |
| `wrappers/<name>.md` | What Juno injects into conversations. Files with `## <key>` sections hold several variants. |
| `resume-recap.md` | Sent on Resume after an interruption or crash. |

## Syntax
- `{{name}}`: replaced with the value. Values are plain text rendered by the app (e.g. `{{contacts}}` is a ready-made
  list). Never tokens or secrets.
- `{{#name}}…{{/name}}`: kept only when `name` is set and not empty/false.
- `{{^name}}…{{/name}}`: kept only when `name` is unset, empty or false.
- In keyed files, a section's text runs from its `## <key>` line to the next heading, trimmed. Text before the first
  `## ` heading (title, notes) is documentation, not sent.
- An unknown placeholder is a bug: rendering fails loudly in tests.

## Placeholders used by system prompts and command templates
- `{{agent_name}}`, `{{profile_name}}`: the agent's name and profile name (so duplicated profiles stay correct).
- `{{fresh_start_threshold}}`: the setting, as a number (orchestrator).
- `{{build_and_test_commands}}`: the project's build & test commands, or `none` (coder).
- `{{args}}`: text typed after a profile command; empty if none.
