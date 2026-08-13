#!/usr/bin/env bash
# EXPERIMENTAL ONLY: this script builds a future optional Node.js AAR payload.
# It is not part of the v1 Core Profile, v1 Gate, or current SDK deliverable.
# Builds a minimal, relocatable Node.js payload for the UGK Android terminal
# runtime. Node's own Android configuration entry point supports Linux and
# macOS only; this project intentionally makes Linux (normally Docker on a
# Windows development machine) the reproducible release path.
set -euo pipefail

readonly NODE_VERSION="24.19.0"
readonly NODE_ARCHIVE="node-v$NODE_VERSION.tar.gz"
readonly NODE_ARCHIVE_URL="https://nodejs.org/dist/v$NODE_VERSION/$NODE_ARCHIVE"
readonly NODE_ARCHIVE_SHA256="16fe258006a6e86844fbe05b3b5e1e5623ca8d3da54e32d98d9e83234bf25b01"
readonly OPENSSL_MODULES_DIRECTORY="/nonexistent"
readonly REQUIRED_NDK_REVISION="28.2.13676358"

usage() {
  cat <<'EOF'
Usage: build-node-android.sh <arm64-v8a|x86_64>

Required environment variables:
  ANDROID_NDK_ROOT          Linux Android NDK root (r28c / 28.2.13676358)
  UGK_TERMINAL_VENDOR_DIR   Persistent source/build cache outside the Git tree

Optional environment variables:
  UGK_TERMINAL_NODE_OUTPUT  Output .so path. Defaults to this repository's
                            ugk-terminal-runtime-android/src/main/jniLibs path.
  UGK_TERMINAL_NODE_WORK_ROOT
                            Parent directory for the temporary source and build
                            tree. Defaults to UGK_TERMINAL_VENDOR_DIR/build.
                            In Docker on Windows, use a Linux container path
                            such as /tmp/ugk-node-build to avoid slow bind I/O.
  UGK_TERMINAL_OVERWRITE=1  Permit replacing an existing output payload.
  UGK_TERMINAL_JOBS=1       Parallel make job count. Keep this low in Docker.

Host requirements: Linux, Python 3.9+, GNU make, a C/C++ compiler, patch,
tar, curl and sha256sum. On Windows, run this script inside a Linux Docker
container with the repository and UGK_TERMINAL_VENDOR_DIR mounted from E:.
EOF
}

if [[ $# -ne 1 ]]; then
  usage >&2
  exit 64
fi

readonly TARGET_ABI="$1"
case "$TARGET_ABI" in
  arm64-v8a)
    readonly TARGET_TRIPLE="aarch64-linux-android"
    readonly NODE_DEST_CPU="arm64"
    readonly GYP_ARCH="arm64"
    ;;
  x86_64)
    readonly TARGET_TRIPLE="x86_64-linux-android"
    readonly NODE_DEST_CPU="x64"
    readonly GYP_ARCH="x64"
    ;;
  *)
    echo "Unsupported ABI: $TARGET_ABI" >&2
    usage >&2
    exit 64
    ;;
esac

if [[ "$(uname -s)" != "Linux" ]]; then
  echo "Node.js Android builds are release-supported here only on Linux; use Docker on Windows." >&2
  exit 64
fi

: "${ANDROID_NDK_ROOT:?Set ANDROID_NDK_ROOT to the Linux Android NDK root.}"
: "${UGK_TERMINAL_VENDOR_DIR:?Set UGK_TERMINAL_VENDOR_DIR to an external build cache.}"

readonly NDK_ROOT="$ANDROID_NDK_ROOT"
readonly VENDOR_DIR="$UGK_TERMINAL_VENDOR_DIR"
readonly REPOSITORY_ROOT="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
readonly DEFAULT_OUTPUT="$REPOSITORY_ROOT/ugk-terminal-runtime-android/src/main/jniLibs/$TARGET_ABI/libugk_node.so"
readonly OUTPUT_PATH="${UGK_TERMINAL_NODE_OUTPUT:-$DEFAULT_OUTPUT}"
readonly JOBS="${UGK_TERMINAL_JOBS:-1}"
readonly NDK_HOST_TAG="linux-x86_64"
readonly TOOLCHAIN_BIN="$NDK_ROOT/toolchains/llvm/prebuilt/$NDK_HOST_TAG/bin"
readonly CLANG="$TOOLCHAIN_BIN/$TARGET_TRIPLE"24-clang
readonly CLANGXX="$TOOLCHAIN_BIN/$TARGET_TRIPLE"24-clang++
readonly ARCHIVE_DIR="$VENDOR_DIR/sources"
readonly ARCHIVE_PATH="$ARCHIVE_DIR/$NODE_ARCHIVE"
readonly WORK_ROOT="${UGK_TERMINAL_NODE_WORK_ROOT:-$VENDOR_DIR/build}"
readonly WORK_DIR="$WORK_ROOT/node-$NODE_VERSION-android-$TARGET_ABI-$(date +%Y%m%d%H%M%S)-$$"
readonly SOURCE_DIR="$WORK_DIR/node-v$NODE_VERSION"

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Required command is missing: $1" >&2
    exit 69
  fi
}

download_if_missing() {
  local url="$1"
  local output="$2"
  local temporary_output="$output.partial.$$"
  mkdir -p "$(dirname -- "$output")"
  if [[ -f "$output" ]]; then
    return
  fi
  curl --fail --location --retry 3 --output "$temporary_output" "$url"
  mv -f "$temporary_output" "$output"
}

verify_sha256() {
  local file="$1"
  local expected="$2"
  local actual
  actual="$(sha256sum "$file" | awk '{print $1}')"
  if [[ "$actual" != "$expected" ]]; then
    echo "SHA-256 mismatch for $file" >&2
    echo "Expected: $expected" >&2
    echo "Actual:   $actual" >&2
    exit 65
  fi
}

publish_file() {
  local source="$1"
  local destination="$2"
  local staged="$destination.tmp.$$"
  mkdir -p "$(dirname -- "$destination")"
  cp "$source" "$staged"
  mv -f "$staged" "$destination"
}

patch_openssl_modules_directory() {
  local gyp_path="$1"
  python3 - "$gyp_path" "$OPENSSL_MODULES_DIRECTORY" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
replacement = sys.argv[2]
content = path.read_text(encoding="utf-8")
old_paths = (
    "<(PRODUCT_DIR_ABS_CSTR)/obj/lib/openssl-modules",
    "<(PRODUCT_DIR_ABS_CSTR)/obj.target/deps/openssl/lib/openssl-modules",
)
replacements = 0
for old_path in old_paths:
    count = content.count(old_path)
    if count == 0:
        raise SystemExit(f"Node OpenSSL module directory was not found: {old_path}")
    content = content.replace(old_path, replacement)
    replacements += count
if replacements != 3:
    raise SystemExit(f"Unexpected Node OpenSSL module-directory replacement count: {replacements}")
path.write_text(content, encoding="utf-8")
PY
}

patch_native_addons_disabled() {
  local header_path="$1"
  python3 - "$header_path" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
content = path.read_text(encoding="utf-8")
old = """inline bool Environment::no_native_addons() const {
  return (flags_ & EnvironmentFlags::kNoNativeAddons) ||
          !options_->allow_native_addons;
}
"""
new = """inline bool Environment::no_native_addons() const {
  return true;
}
"""
if content.count(old) != 1:
    raise SystemExit("Node native-addon guard changed unexpectedly")
path.write_text(content.replace(old, new), encoding="utf-8")
PY
}

patch_v8_trap_handler() {
  local header_path="$1"
  python3 - "$header_path" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
content = path.read_text(encoding="utf-8")
start_marker = "// X64 on Linux, Windows, MacOS, FreeBSD."
end_marker = "#if V8_OS_ANDROID && V8_TRAP_HANDLER_SUPPORTED"
replacement = """// Android builds do not enable V8's signal-based trap handler.
#define V8_TRAP_HANDLER_SUPPORTED false

"""

start = content.find(start_marker)
end = content.find(end_marker, start if start >= 0 else 0)
if start >= 0 and end >= 0:
    content = content[:start] + replacement + content[end:]
elif "#define V8_TRAP_HANDLER_SUPPORTED false" not in content:
    raise SystemExit("V8 trap-handler header layout changed unexpectedly")

path.write_text(content, encoding="utf-8")
if content.count("#define V8_TRAP_HANDLER_SUPPORTED false") != 1:
    raise SystemExit("V8 trap-handler support was not disabled exactly once")

for suffix in (".orig", ".rej"):
    auxiliary = path.with_name(path.name + suffix)
    if auxiliary.exists():
        auxiliary.unlink()
PY
}

if [[ ! -x "$CLANG" || ! -x "$CLANGXX" ]]; then
  echo "Android target compiler is missing: $CLANG" >&2
  exit 66
fi
if [[ ! -f "$NDK_ROOT/source.properties" ]] || ! grep -Fxq "Pkg.Revision = $REQUIRED_NDK_REVISION" "$NDK_ROOT/source.properties"; then
  echo "Node Android build requires NDK r28c ($REQUIRED_NDK_REVISION): $NDK_ROOT" >&2
  exit 66
fi
if [[ ! -x "$TOOLCHAIN_BIN/llvm-strip" || ! -x "$TOOLCHAIN_BIN/llvm-readelf" ]]; then
  echo "Android LLVM tools are missing from: $TOOLCHAIN_BIN" >&2
  exit 66
fi
for command in curl make python3 sha256sum tar; do
  require_command "$command"
done
if [[ -e "$OUTPUT_PATH" && "${UGK_TERMINAL_OVERWRITE:-0}" != "1" ]]; then
  echo "Refusing to overwrite existing payload: $OUTPUT_PATH" >&2
  exit 73
fi

download_if_missing "$NODE_ARCHIVE_URL" "$ARCHIVE_PATH"
verify_sha256 "$ARCHIVE_PATH" "$NODE_ARCHIVE_SHA256"

mkdir -p "$WORK_ROOT"
mkdir -p "$WORK_DIR"
tar -xzf "$ARCHIVE_PATH" -C "$WORK_DIR"
cd "$SOURCE_DIR"

# Node's bundled android-configure helper contains an older patch hunk for
# Node v24.19.0 and does not propagate patch(1)'s exit status. Apply the same
# semantic fix against the current header and fail if the layout changes.
patch_v8_trap_handler deps/v8/src/trap-handler/trap-handler.h
grep -Fxq '#define V8_TRAP_HANDLER_SUPPORTED false' deps/v8/src/trap-handler/trap-handler.h
test ! -e deps/v8/src/trap-handler/trap-handler.h.rej
test ! -e deps/v8/src/trap-handler/trap-handler.h.orig

# Node's generated OpenSSL configuration otherwise embeds the absolute build
# directory in MODULESDIR. This runtime has no external providers, so use a
# fixed inaccessible location and retain no host- or worktree-specific path.
patch_openssl_modules_directory deps/openssl/openssl.gyp

# The baseline profile has no npm and never loads arbitrary .node files from
# the app-private workspace. Keep this as a build-time invariant rather than a
# wrapper flag that a script could override with --addons. This narrows Node's
# module surface but does not turn the surrounding Bash Runtime into a sandbox.
patch_native_addons_disabled src/env-inl.h

export PATH="$TOOLCHAIN_BIN:$PATH"
export CC="$CLANG"
export CXX="$CLANGXX"
export AR="$TOOLCHAIN_BIN/llvm-ar"
export NM="$TOOLCHAIN_BIN/llvm-nm"
export RANLIB="$TOOLCHAIN_BIN/llvm-ranlib"
export STRIP="$TOOLCHAIN_BIN/llvm-strip"
# GYP otherwise falls back from CC_host/CXX_host to the Android target
# compiler. Node builds several V8 snapshot helpers for the Linux build host;
# they must use glibc's gcc/g++, not the Android sysroot.
export CC_host="${CC_host:-gcc}"
export CXX_host="${CXX_host:-g++}"
export AR_host="${AR_host:-ar}"
export NM_host="${NM_host:-nm}"
export RANLIB_host="${RANLIB_host:-ranlib}"
export LINK_host="${LINK_host:-g++}"
export CFLAGS_host="${CFLAGS_host:-}"
export CXXFLAGS_host="${CXXFLAGS_host:-}"
export LDFLAGS_host="${LDFLAGS_host:-}"
export CFLAGS='-O2 -fPIE'
# Do not introduce libc++_shared.so into the host application. Its SONAME is
# global to nativeLibraryDir and can otherwise collide with an unrelated NDK
# dependency selected by the consuming app.
export CXXFLAGS='-O2 -fPIE -static-libstdc++'
export LDFLAGS='-fPIE -pie -static-libstdc++ -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 -Wl,-z,relro,-z,now'
export GYP_DEFINES="target_arch=$GYP_ARCH v8_target_arch=$GYP_ARCH android_target_arch=$GYP_ARCH host_os=linux OS=android android_ndk_path=$NDK_ROOT"

# V8 lite mode avoids the Android cross-build trap-handler mismatch in the
# host mksnapshot helper. It keeps JavaScript, TLS, crypto and child_process;
# WebAssembly is intentionally outside this minimal terminal profile.
./configure \
  --dest-cpu="$NODE_DEST_CPU" \
  --dest-os=android \
  --openssl-no-asm \
  --cross-compiling \
  --without-npm \
  --without-corepack \
  --without-intl \
  --v8-lite-mode \
  --without-inspector \
  --without-amaro \
  --without-node-options \
  --without-sqlite

make -j"$JOBS" node
"$TOOLCHAIN_BIN/llvm-strip" --strip-unneeded out/Release/node

for build_path_marker in "$VENDOR_DIR" "$WORK_ROOT" '/work/'; do
  if grep --binary-files=text --quiet --fixed-strings -- "$build_path_marker" out/Release/node; then
    echo "Node payload retained an absolute build path: $build_path_marker" >&2
    exit 70
  fi
done

if "$TOOLCHAIN_BIN/llvm-readelf" -d out/Release/node | grep --quiet --fixed-strings 'Shared library: [libc++_shared.so]'; then
  echo 'Node payload must not depend on libc++_shared.so.' >&2
  exit 70
fi

publish_file out/Release/node "$OUTPUT_PATH"

echo "Node payload: $OUTPUT_PATH"
"$TOOLCHAIN_BIN/llvm-readelf" -h -l -d "$OUTPUT_PATH"
