---
name: android-skill-creator
description: Authoring SOP for creating, updating, querying, deleting, and using UGK Android file-backed skills.
x-ugk-load: indexed
triggers: skill, create skill, update skill, save skill, delete skill, use skill, skill authoring
---
# UGK Android skill authoring SOP

This is the SOP for the UGK Android file-backed skill format. It is not the
generic Codex `skill-creator` format. The current MVP supports one `SKILL.md`
file per skill and does not install scripts, assets, or supporting resources.

## What counts as an installed skill

An installed skill is exactly:

`<filesDir>/agent-skills/<name>/SKILL.md`

The `name` directory must match the frontmatter `name`, which must match
`[a-z0-9-]+`. A normal `docs/*.md` file is source material only; it is not an
installed or created skill until `skill_save` writes it to the skill repository.

The required flat frontmatter keys are `name` and `description`. Optional UGK
keys are `x-ugk-load` (`always`, `indexed`, or `triggered`), `triggers`, and
`x-ugk-embed-files`. The body must be non-empty and stay within the runtime
limits. Do not write arbitrary paths or hand-edit files outside this format.

## Create

1. If the user has provided source material in `docs/` or elsewhere, treat it
   as input to shape into a concise skill body; do not call that file the
   installed skill.
2. Call `skill_list` first. Choose a legal, non-protected name and check for
   an existing skill.
3. Call `skill_save` with structured `name`, `description`, and `body` fields.
   Add `loadPolicy`, `triggers`, or `embedFiles` only when they are needed.
   For a new name leave `overwrite` false or omit it.
4. Verify the result: call `skill_list`, confirm the name has `status: valid`,
   then call `skill_read` and check the complete manifest and body.

## Update

1. Call `skill_list` and then `skill_read` for the exact skill name before
   changing it. Preserve useful existing behavior and manifest fields.
2. Obtain the user's consent when the update changes the Agent's behavior.
3. Call `skill_save` with the complete replacement body and
   `overwrite: true`. Never use overwrite to modify `agent-memory` or this
   `android-skill-creator` skill; those built-ins are protected.
4. Repeat `skill_list` and `skill_read` verification. A successful response
   means the new file is valid and will be used on the next Agent run.

## Query and use

Use `skill_list` to discover names, descriptions, load policies, and invalid
entries. Use `skill_read` to load the complete manifest and body, especially
for an `indexed` skill before relying on it. `always` skills are injected every
run, `indexed` skills provide metadata until read, and `triggered` skills are
selected by their trigger keywords. Do not claim that an indexed or triggered
skill was used until its required content has been selected or read.

## Delete

Call `skill_list` and `skill_read` to confirm the exact target and what will be
removed. After explicit user consent, call `skill_delete` with only the skill
name. It deletes one repository child directory; it never accepts a path.
`agent-memory` and `android-skill-creator` cannot be deleted.

## Completion definition

Never report “skill created”, “updated”, or “verified” merely because a draft
document exists. The operation is complete only when `skill_save` succeeds,
`skill_list` reports `status: valid`, and `skill_read` confirms the saved
manifest and body. If any check fails, report the validation error and do not
claim installation.
