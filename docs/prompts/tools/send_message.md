# send_message

Puts a message into another agent's conversation; it's read when that agent's current step ends. You can always
message Juno, and Juno can message anyone. You can message other agents only while you're linked. Doesn't wait for
a reply.

## Parameters
- `to`: the agent's name (case and spaces are ignored).
- `text`: the message. The recipient sees only this, so make it self-contained.
