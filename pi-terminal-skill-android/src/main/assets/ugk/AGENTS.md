# AGENTS.md — UGK Android SDK Runtime Agent Contract

This file is for the Agent running inside the Android SDK. It is not the
repository-root `AGENTS.md`, which only governs development work by Codex and
other repository contributors.

Treat this document as the authoritative description of the packaged terminal
environment. Follow it together with the tool confirmation policy on every
turn. Do not claim that a command succeeded unless the tool result proves it.

## Runtime boundary

- `terminal_bash_execute` already receives non-interactive Bash source code.
- The SDK starts its own packaged Bash process inside the host app's private
  files directory. There is no terminal UI, TTY, shell login, or interactive
  prompt.
- The process uses the host application's Android UID. This runtime is not a
  security sandbox and cannot access arbitrary other applications' private
  data.
- The environment is deliberately curated. Do not assume a normal Linux
  distribution, Android shell, Termux installation, package manager, or
  writable system directories.

## Available commands

The v1 core profile provides:

- Bash built-ins and Bash scripting;
- `python` and `python3` (CPython 3.14.6);
- `sqlite3` (SQLite CLI);
- `curl`;
- `openssl`.

Node.js, Git, OpenSSH/`ssh`, `jq`, `npm`, `pip`, `apt`, and `pkg` are not part
of this profile. Do not retry them as if they were installed.

`python` and `python3` are Bash functions injected by the SDK's `BASH_ENV`.
They are direct commands inside the current Bash script, but they are not
ordinary executable files discoverable by another program. Therefore
`python3 script.py` and `python3 -m module` are valid, while the following are
not valid in this Runtime:

- `nohup python3 ...`
- `env python3 ...`
- `setsid python3 ...`
- `xargs python3 ...`

Those external programs try to `exec` a real file named `python3` and cannot
see the Bash function. Do not work around this by guessing a path; use the
prebuilt Runtime-managed Tool when the task needs a persistent service.

## Command translation rules

- Write the requested script directly as the `script` argument to
  `terminal_bash_execute`.
- Never invoke `bash`, `bash -c`, `sh`, or `sh -c` as a child command. The tool
  is already executing Bash; use the script body directly.
- Use `python` or `python3` directly, and use `sqlite3` for SQLite CLI work.
- For HTTP or HTTPS work use the packaged `curl` directly. Network access is
  still subject to the tool's confirmation policy and the user's request.
- Do not use a package manager to install missing tools. If a required command
  is outside the profile, report that limitation.

## Long-running local HTTP services

- Do not start a persistent HTTP server with `terminal_bash_execute`, `nohup`,
  `disown`, `setsid`, or a shell background job. That Tool is for bounded
  one-shot scripts and its process group is intentionally tied to one call:
  when the call returns, the Runtime terminates any background process left
  inside that call's process group. Such processes do not survive the Tool
  call, so a long-running service must use the managed tool below.
- Use `local_http_server_start` for a website or static directory that must be
  reachable by the browser. It launches the verified packaged CPython through
  the SDK's process-session manager, binds only to `127.0.0.1`, records its
  process group and log, and requires the normal user confirmation flow.
- Use `local_http_server_status` after starting and before claiming that the
  site is available. This is a read-only check and does not require user
  confirmation.
- Use `local_http_server_stop` when the user asks to stop the service. It only
  stops a service owned and recorded by this Runtime and requires confirmation.
- The returned `http://127.0.0.1:<port>/` URL is reachable by another App on
  the same Android device, such as a browser. It is not a LAN/public URL and
  an app-private filesystem path must never be passed to the browser.

## Execution and safety

- The default terminal policy requires the user confirmation flow. When the
  exposed tool is wrapped for confirmation, call that flow first; do not try
  to bypass, simulate, or repeat confirmation silently. A host may explicitly
  disable the wrapper for a trusted session. If the host injects a later,
  session-scoped full-authorization instruction, that instruction is the
  authoritative exception: do not call `show_user_confirmation_dialog` and
  continue to preserve every Tool-specific validation and result check.
- Prefer one short, deterministic, non-interactive script per requested check.
- Use absolute paths only when the tool result or runtime contract provides
  them. Treat the current working directory and managed environment variables
  as authoritative.
- Keep output bounded, avoid infinite loops and daemon processes in
  `terminal_bash_execute`, and stop when the requested result is established.
  Persistent local services belong to the dedicated local HTTP tools, not to
  a Bash background process; any background process still alive when the call
  ends is terminated together with the call's process group.
- Separate local verification from network access. Do not add a network call to
  a local component test unless the user explicitly asks for it.

## Reporting failures

When a command fails, report the actual exit code and relevant stderr. If the
failure is caused by an unavailable command, correct the script using the
translation rules above rather than invoking another shell or guessing that a
package is installed.
