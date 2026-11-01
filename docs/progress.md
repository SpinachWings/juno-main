# Progress

## Milestone 0: prompts. Done (2026-11-01)

### Done
- All built-in natural-language text, approved by the user, in `docs/prompts/` (format and placeholders in
  `docs/prompts/README.md`):
  - system prompts of the six built-in profiles (`profiles/`);
  - the coder's `/checkforcomments` template (`commands/coder-checkforcomments.md`);
  - agent preamble, Juno MCP tool and parameter descriptions, tool results and refusals, wrappers, resume recap.
- `CLAUDE.md` and this file.

### Stubbed
Nothing (no code in this milestone).

### Decisions (not in the spec)
1. **Questions (❓):** an agent that needs the user's answer makes it the last paragraph of its reply, starting with
   `❓ `, and ends its turn. The runtime marks **Awaiting decision ❓** when a turn's final reply has such a
   paragraph. (Claude Code's `AskUserQuestion` via `request_permission` was the alternative; not chosen.)
2. **Reports:** the agent calls `report` once with its own summary. The app adds status, task, files changed and
   context use (`wrappers/report.md`) and caps the summary at Report length. If an agent ends a Juno task without
   calling `report`, the app uses its final reply, truncated.
3. **Reviewer, head moved before posting:** the reviewer checks the head commit before posting; if it moved it calls
   `start_review` again on the same target, which re-waits for pipelines and refreshes the snapshot.
4. **`/checkforcomments`:** unresolved threads whose latest comment is the coder's own reply are left out and listed
   as "already answered" (the coder can't resolve threads, so "reply only" threads would otherwise come back every
   run).
5. **Template syntax:** `{{x}}` plus Mustache-style `{{#x}}…{{/x}}` / `{{^x}}…{{/x}}` sections; no other logic.
6. **File layout:** results are one file per tool with `## <key>` sections; wrappers with variants likewise.
7. **`set_link`** has an extra optional `reason` parameter (required when `requested_by_user` is false), shown on
   the approval card's "Why: …".
8. **`get_agent_output`:** `lines` defaults to 100, max 1000.
9. **Placeholders in system prompts:** `{{agent_name}}`, `{{profile_name}}`, `{{fresh_start_threshold}}`,
   `{{build_and_test_commands}}` (`none` when the project has none); `{{args}}` for profile commands.
10. Reviewer verdicts: "Ready to merge" / "Ready after fixes" / "Needs rework". Reviews diff with
    `git diff <base>...<head>`; re-reviews with `git diff <sha>..<head>`.

### Known issues
- Host MCP tool names aren't known yet (§13, Milestone 5a). The prompts say "the host's MCP tools" generically;
  profiles' allowed/disallowed tool lists need the real names.

### For the next milestone (1)
- Built-in profiles load their system prompts from `docs/prompts/profiles/`. Suggested: add `docs/prompts` as a
  Maven resource directory (target path `dev/juno/prompts`) so there's one copy, read from the classpath.
- Settings → Commands lists these templates read-only.
- Build, test and run commands in `CLAUDE.md` are the intended ones; make them true.
