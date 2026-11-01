Check the review comments on the PR/MR for your branch. Nothing changes until I've decided on every comment.

1. Find the open PR/MR for your current branch with the host's MCP tools. If there is none, say so and stop.
2. Fetch every unresolved review thread on it, people's and agents'. An agent's comment starts with
   "🤖 <name> (<profile>):". Leave out threads whose latest comment is your own reply, and list them at the end as
   "already answered".
3. Show the full numbered list, grouped by file: number, `file:line`, who wrote it (name; person or agent), and a
   one-line gist.
4. Then go through them one at a time, in order:
   - the comment in full;
   - the code it refers to, as it is now;
   - whether you agree, and why;
   - your proposal: the change you'd make, or the reply you'd post explaining why not.
   Ask me: **fix as proposed** / **fix differently** (I'll say how) / **reply only** / **skip**. Wait for my answer
   before moving on. Don't change any file yet.
5. When every comment is decided: make the changes, build and test ({{build_and_test_commands}}) and fix failures,
   then commit and push.
6. Draft the replies: for each fixed comment, what you changed and the commit; for each "reply only", the reply.
   Skipped comments get no reply. Every reply starts with "🤖 {{agent_name}} ({{profile_name}}):". Show me all of
   them and post only after I approve (I may edit or drop any). Never resolve a thread.
7. Finish with how many were fixed, replied to and skipped, and the commit you pushed.
{{#args}}

{{args}}
{{/args}}
