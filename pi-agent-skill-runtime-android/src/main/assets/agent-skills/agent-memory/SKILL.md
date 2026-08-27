---
name: agent-memory
description: Captures and replays durable user preferences, rules, profile, and facts across sessions, always asking for explicit consent before writing or deleting memory.
x-ugk-load: always
x-ugk-embed-files: memory:preferences.md, memory:rules.md
---

# Agent Memory

This skill makes the Agent remember durable user information across sessions.
The `memory_list`, `memory_read`, `memory_write`, and `memory_delete` tools
manage the app-private `agent-memory` store. Categories:

- `preferences`: how the user wants you to interact (tone, language, format, verbosity).
- `rules`: standing operating rules the user set for you ("always ...", "never ...").
- `user-profile`: how to address the user, identity, and basic situation.
- `facts`: concrete facts (devices, frequent apps, account suffixes, and similar).

## Every turn

- The embedded `memory:preferences.md` and `memory:rules.md` sections below
  are live reads of the memory store; apply them directly without calling
  tools. A "(embed file not found; skipped)" note means that category has no
  recorded entries yet, not that something is broken.
- When the topic involves the user's preferences, rules, profile, or stored
  facts, call `memory_list` / `memory_read` first (notably for
  `user-profile` and `facts`, which are not embedded), then answer from what
  you actually read. Never invent or guess memories.

## Capturing (requires explicit consent)

Capture when the user explicitly asks to remember something, or states a
durable preference (tone / language / format / verbosity), a standing rule,
identity information, or a fact worth keeping. Common Chinese phrasings:
"记一下", "帮我记住", "以后都用", "别再", "我是做xx的", "我的xx是".

Protocol before any write:

1. Propose first in the conversation: state the category and the exact content
   you intend to save.
2. Only after the user clearly agrees: `memory_read` the category, merge the
   new entry without losing any existing entry, then `memory_write` the whole
   file with `overwrite=true`.
3. Confirm briefly with what was saved.

Never write memory without explicit user consent in the conversation.

## Deleting

When the user asks to forget something, repeat the exact content that will be
deleted, get confirmation, then call `memory_delete` for that category (it
requires a confirmation dialog in normal mode).

## Entry format

One entry per line in the category file, newest last, for example:
`- [2026-08-27] Reply in Chinese (user request)`. Keep entries short and merge
duplicates; never drop existing entries when merging.
