# Progress

## Milestone 1: foundation and settings. Done (2026-11-01)

### Done
- Maven project (Java 27, JavaFX 27.0.1, Jackson, RichTextFX, java-keyring), `dev.juno` packages per §10.
  `docs/prompts` is a Maven resource directory (target `dev/juno/prompts`): one copy, read from the classpath
  (`prompts.Prompts`: load, `## key` sections, `{{x}}` / `{{#x}}` / `{{^x}}` rendering, `listAll`).
- Theme: one stylesheet (`ui/juno.css`, palette at the top), bundled Share Tech Mono and VT323 (OFL licences included).
- Main window shell: toolbar (Add agent, Board / Notes toggle, usage meter, `?`, Settings; all stubs except Settings and
  `?`), Claude Code version banner.
- `model` + `store`: all settings as plain data in `~/.juno/settings.json` (or `JUNO_HOME`), atomic writes
  (`AtomicFiles`). Built-in profiles come from code on every load; only model, effort, executable path, max turns,
  spend limit and own profile commands are taken from the saved copy.
- Settings window, all six tabs working and saved:
  - **Keys:** add/edit/delete, token in the OS vault via java-keyring (`keyring:juno/key/<id>`) or `env:VARIABLE`,
    never plain text, masked, MCP URL from host with override. Test button stubbed.
  - **System settings:** everything in §5.2; Claude hook install / update / uninstall / status; Claude Code path with a
    Check button. Storage section stubbed.
  - **Projects:** fields incl. build & test commands, validated (folder exists, remote well formed, unique name).
  - **Agent profiles:** locks on built-ins, Duplicate, delete for own profiles, profile commands (built-in ones locked).
  - **Skills:** import (SKILL.md / folder / .zip / .skill), whole-orchestration or per-profile assignment, global and
    per-profile skill switches, bundled superpowers (obra/superpowers, MIT, commit 2ce8aa1) with locked coder/planner
    assignments, refreshed from the app's copy on every start-up.
  - **Commands:** table from one registry (`runtime.CommandRegistry`, which later milestones extend), plus every
    built-in prompt file read-only.
- First-run setup (§5.1.2): Claude Code check, hook, notes folder, first project, keys (opens the Keys tab).
- Claude Code version check (`ClaudeVersion`, minimum 2.1.314) at start-up, on path change, and in first-run setup.
- 21 unit tests (`mvn verify`): prompts and profiles, settings round trip and validation, MCP URLs, token masking, hook
  merge / idempotence / uninstall / backups / the hook script itself (against a scratch Claude folder), version check,
  skills import / zip safety / bundled refresh / resolver.
- Checked by eye: `ScreenshotTool` (test sources, not a unit test; scratch home, scratch Claude folder, in-memory token
  store) renders the main window, all six tabs and every first-run step to PNG; the app was also started with
  `mvn javafx:run` against a scratch `JUNO_HOME` and `CLAUDE_CONFIG_DIR`.

### Changes after review (same session)
- Profiles tab: **+ New profile** and Duplicate both save immediately; **Available tools...** opens a reference of tools
  and rule syntax (`model.ToolCatalog`, extend it in Milestone 5 with the host MCP tools) with add-to-allowed /
  disallowed buttons for non-built-in profiles.
- Skills tab: no global "Enabled" column (skills are switched per profile only; `SkillPack.disabledSkills` stays in the
  model but has no UI). Bundled packs can be assigned only to their locked profiles (coder, planner) and own
  profiles, never other built-ins; the resolver ignores such assignments. The tab refreshes when shown.

### Stubbed
- Keys: Test button (Milestone 5). Storage section, Remove worktree, Clean up old data (Milestone 3).
- Toolbar: Add agent (Milestone 3), usage meter (Milestone 2), Notes toggle (Milestone 6). The board is a placeholder.
- Notes-folder overlap check with project folders (Milestone 6, §9).
- Host MCP tools in the coder / reviewer allowed tools (Milestone 5, once names are verified, §13).
- Per-agent plugin folder from `SkillResolver` (Milestone 2); the resolver already gives the enabled skills per profile.

### Decisions (not in the spec)
1. **No `module-info`:** the app runs from the classpath through `dev.juno.app.Launcher` (a plain main class), the
   usual way to start JavaFX without a module-path launcher.
2. **Hook transport:** the hook is `~/.juno/hooks/juno-hook.sh`, registered for `Notification` and `Stop` (timeout 5 s)
   as `sh "<path>"`. It does nothing unless `JUNO_AGENT_ID` is set, otherwise appends Claude's hook JSON line to
   `~/.juno/hook-events/<agent>.jsonl` and exits 0, so it works with Juno closed. Milestone 2 tails those files.
   `sh` is used on Windows too, since Claude Code there runs hooks through Git Bash; **Verified after the Milestone 1 commit
   (Claude Code 2.1.314, Windows, haiku/low, scratch `CLAUDE_CONFIG_DIR` with only the login file copied in): the `Stop`
   hook fires through `sh` and writes the event JSON; with no `JUNO_AGENT_ID` nothing is written.** `Notification` wasn't
   exercised. Our entries are recognised by the script name; status is *outdated* when
   an event is missing, the command differs or the script text differs. Backups are
   `settings.json.juno-<timestamp>.bak` next to the settings file, only when a change is made. Claude's folder is
   `CLAUDE_CONFIG_DIR` if set, else `~/.claude`.
3. **Minimum Claude Code version** is 2.1.314, the version installed during development; flags aren't verified yet
   (Milestone 2 raises it if needed).
4. **Profile editing:** built-in profiles lock name, provider, permission mode, system prompt, allowed / disallowed
   tools, needs folder and edits files (§5.4). There is no "new blank profile": Duplicate is the way to make one.
5. **Skills assignment model:** a pack is assigned either to the whole orchestration or to chosen profiles; bundled
   packs' locked assignments live in code (`BundledPacks`), and a user can additionally assign them to own profiles.
   Importing a pack whose name exists makes a new pack with a numbered id. Imported packs default to whole
   orchestration. A pack's entry skill is `using-<id>` if present, else its first skill.
6. **Keys:** a key's host-less `Test` and MCP URL: GitHub Enterprise Server hosts have no URL unless an override is set.
   `<name>.ghe.com` uses `https://copilot-api.<name>.ghe.com/mcp` as the spec says (still "verify").
7. **Defaults not in the spec:** max agents working at once 4, idle process timeout 10 minutes.
8. **Settings in one file:** keys, projects, profiles, skill packs and system settings are all in `settings.json`.

### Known issues
- The `Notification` hook event hasn't been exercised against the real CLI (`Stop` has; see decision 2).
- The Commands table truncates long descriptions with an ellipsis (no row wrapping).
- Bundled superpowers is copied from the upstream repository's `skills/` folder only; its hooks and other plugin parts
  are not used.

### For the next milestone (2)
- `AppContext` holds `settings`, `provider` (`ClaudeCodeProvider`), `skills` and `skillResolver`; call `ctx.save()`
  after changing settings. `Provider` has only the version check and hook so far: extend it.
- Per-agent skills plugin folder: build from `SkillResolver.enabledFor(settings, profileName)` and
  `SkillLibrary.packDir(pack)/skills/<skill>`.
- Profiles: system prompts are already rendered-ready templates (`{{agent_name}}` etc.); build and test commands come
  from `Project.buildAndTestCommands`.
- Keys resolve through `TokenStore.resolve(key.token)` only when an agent starts.
- Register new slash commands in `CommandRegistry` (the Commands tab reads it).
- `ScreenshotTool` is the way to look at new screens: build the classpath with
  `mvn dependency:build-classpath -Dmdep.outputFile=target/cp.txt`, then
  `java -cp "target/classes;target/test-classes;<cp>" dev.juno.ui.ScreenshotTool <outdir>`.

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
