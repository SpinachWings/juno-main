# Results: start_review

## waiting
Waiting for pipelines on {{target}} (head `{{head}}`). End your turn; a message follows when they finish.

## not-cloned
{{repo}} isn't one of the user's projects. Ask the user to run `git clone {{clone_url}}` and add the folder as a
project in Juno. Then stop; you'll be told when it's there.

## no-remote
{{project}} has no remote, so it can't be reviewed.

## no-key
No {{host_kind}} key is set for {{agent_name}}, so I can't read {{repo}}. Set one in {{agent_name}}'s options menu.

## key-cant-read
{{agent_name}}'s {{host_kind}} key can't read {{repo}}. Use a key with access to it.

## not-found
Couldn't find {{target}}: {{detail}}.

## no-open-pr
{{name}} has no open PR/MR.

## already-open
You already have a review open on {{open_target}}. Call `end_review` first, or call `start_review` on
{{open_target}} to refresh it.
