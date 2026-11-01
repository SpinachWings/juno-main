# assign_task

Juno only. Gives an agent a task. It's delivered at once, or held for the user's approval: depending on settings
when the user didn't name the agent, and always when `fresh` is true. The result says which. A held task's outcome
arrives as a message, and the agent's report comes back to you.

## Parameters
- `to`: the agent's name.
- `task`: the task, self-contained: goal, facts, what done looks like.
- `fresh`: true starts a new conversation for it (always needs the user's approval); false continues the current
  one.
