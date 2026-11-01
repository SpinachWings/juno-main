# start_review

Starts a review of a PR/MR. It finds the repository and the head and base commits, then waits for the pipelines
(you're Waiting ⏳; end your turn, and a message follows). It then makes a snapshot of the head commit your working
folder. If new commits arrived, call it again on the same target to wait again and refresh.

## Parameters
- `target`: a PR/MR number (`#42`, `!42`), a link, or an agent's name (meaning its open PR/MR).
