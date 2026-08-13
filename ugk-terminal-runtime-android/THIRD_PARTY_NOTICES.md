# Third-party notices

## GNU Bash 5.3.15

- Copyright: Free Software Foundation, Inc.
- License: GPL-3.0-or-later.
- Source archive: `https://mirrors.kernel.org/gnu/bash/bash-5.3.tar.gz`
- Exact source SHA-256, upstream patch hashes and built artifact hashes are in
  [`runtime-lock.json`](runtime-lock.json).
- The reproducible build entry point and local Android compatibility changes are
  in [`../scripts/terminal-runtime/build-bash.sh`](../scripts/terminal-runtime/build-bash.sh).
- GPL-3.0 license text: [`licenses/GPL-3.0.txt`](licenses/GPL-3.0.txt).

## SQLite 3.53.4

- License: Public Domain.
- Source archive: `https://www.sqlite.org/2026/sqlite-src-3530400.zip`
- Exact source SHA-256 and built artifact hashes are in
  [`runtime-lock.json`](runtime-lock.json).
- The reproducible Android CLI build entry point is
  [`../scripts/terminal-runtime/build-sqlite.sh`](../scripts/terminal-runtime/build-sqlite.sh).
- The packaged CLI is built with `SQLITE_OMIT_LOAD_EXTENSION`; it cannot load
  native SQLite extensions from writable storage.

## OpenSSL 3.6.3

- License: Apache-2.0.
- Source archive:
  `https://github.com/openssl/openssl/releases/download/openssl-3.6.3/openssl-3.6.3.tar.gz`
- Exact source SHA-256 and built artifact hashes are in
  [`runtime-lock.json`](runtime-lock.json).
- The reproducible Android CLI build entry point is
  [`../scripts/terminal-runtime/build-network-runtime.sh`](../scripts/terminal-runtime/build-network-runtime.sh).
- The first network profile builds a static CLI with no shared libraries,
  tests, modules, legacy provider, engines, or DSO support; SSL3 is disabled.
  TLS commands remain available for curl's OpenSSL backend.

## curl 8.21.0

- License: curl license (MIT-like; see the upstream `COPYING` file).
- Source archive:
  `https://github.com/curl/curl/releases/download/curl-8_21_0/curl-8.21.0.tar.xz`
- Exact source SHA-256 and built artifact hashes are in
  [`runtime-lock.json`](runtime-lock.json).
- The reproducible Android CLI build entry point is
  [`../scripts/terminal-runtime/build-network-runtime.sh`](../scripts/terminal-runtime/build-network-runtime.sh).
- The packaged profile exposes only `file`, `http`, and `https`; SSH, FTP,
  LDAP, mail protocols, HTTP/2, HTTP/3, HSTS, Alt-Svc, WebSocket, libpsl,
  brotli, and zstd are intentionally excluded.

## Mozilla CA Certificate Bundle 2026-07-16

- License: MPL-2.0.
- Source data: `https://curl.se/ca/cacert-2026-07-16.pem`
- Exact SHA-256 and the packaged asset hash are in
  [`runtime-lock.json`](runtime-lock.json).
- The APK asset is copied only as non-executable certificate data into the
  host app's private directory, then SHA-256 verified before `curl` runs.

## CPython 3.14.6 Android embeddable distribution

- License: Python Software Foundation License Version 2 (PSF-2.0); the bundled
  `lib/python3.14/LICENSE.txt` retains the complete upstream notice set.
- Official Android packages:
  `https://www.python.org/ftp/python/3.14.6/python-3.14.6-aarch64-linux-android.tar.gz`
  and
  `https://www.python.org/ftp/python/3.14.6/python-3.14.6-x86_64-linux-android.tar.gz`.
- Exact package, native-library, extension-tree, standard-library manifest and
  archive hashes are in [`runtime-lock.json`](runtime-lock.json).
- The reproducible preparation entry point is
  [`../scripts/terminal-runtime/prepare-python-runtime.ps1`](../scripts/terminal-runtime/prepare-python-runtime.ps1).
  It preserves `libpython` and production extension modules in
  `nativeLibraryDir`; only pure standard-library data is materialized under the
  host app's private files directory.
- CPython test extensions, `test`, IDLE, Tk, `ensurepip`, and pydoc data are
  not part of this runtime profile. `pip` is therefore not bundled.

This repository is currently a development distribution, not a release artifact. Before
shipping an APK/AAB containing `libugk_bash.so`, its distributor must provide
the complete corresponding source and the GPL-3.0 license text in the release
material, including the exact source archive, applied upstream patches and this
project's build/compatibility changes. A source URL alone is not sufficient for
that release obligation.
