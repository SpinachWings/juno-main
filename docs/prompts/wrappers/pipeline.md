# Pipeline-wait outcomes (to the reviewer)

"Needs a person", "failed" and "wait limit reached" go to the user as Awaiting decision ⚠, not to the reviewer.
"Continue" or "wait for a new commit" just keeps waiting, so the reviewer hears about it only through `passed`,
`none` or `cancelled`.

## passed
[Pipelines passed on {{target}}] Head `{{head}}`, base `{{base}}`. Your working folder is now the review snapshot
`{{folder}}`, detached at the head. Checks: {{checks}}.{{#optional_failed}} Failing optional checks (they don't
block; list them in your summary): {{optional_failed}}.{{/optional_failed}} Review now.

## none
[No pipelines on {{target}}] Head `{{head}}`, base `{{base}}`. Your working folder is now the review snapshot
`{{folder}}`. Nothing checked the build or tests, so say so in your summary. Review now.

## cancelled
[Review cancelled] {{reason}}. The snapshot was removed; post nothing.{{#from_juno}} Report "cancelled" to
Juno.{{/from_juno}}
