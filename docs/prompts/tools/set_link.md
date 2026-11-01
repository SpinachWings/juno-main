# set_link

Juno only. Opens or closes a link between two agents other than Juno. Opening one the user didn't ask for is held
until they approve. Closing is immediate.

## Parameters
- `a`: the first agent's name.
- `b`: the second agent's name.
- `open`: true to open, false to close.
- `requested_by_user`: true only if the user asked for this link in their own words; otherwise false.
- `reason`: why, in one sentence; shown on the approval card. Required when `requested_by_user` is false.
