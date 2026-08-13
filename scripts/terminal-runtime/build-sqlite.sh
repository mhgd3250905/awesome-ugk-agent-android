#!/usr/bin/env bash
# Builds the SQLite CLI as a relocatable Android native payload.
#
# The resulting ELF is deliberately named as an Android native library so it
# is installed as a real file in nativeLibraryDir. Bash invokes it through a
# generated BASH_ENV function; no executable is copied to app-writable data.
set -euo pipefail

readonly SQLITE_VERSION="3.53.4"
readonly SQLITE_SOURCE_ID="3530400"
readonly SQLITE_ARCHIVE="sqlite-src-$SQLITE_SOURCE_ID.zip"
readonly SQLITE_ARCHIVE_SHA256="d18fa15aec74d8c17e1463f861095adc01b5ad190256acb4f91d22f0368d232b"
readonly SQLITE_SOURCE_URL="https://www.sqlite.org/2026/$SQLITE_ARCHIVE"

usage() {
  cat <<'EOF'
Usage: build-sqlite.sh <arm64-v8a|x86_64>

Required environment variables:
  ANDROID_NDK_ROOT          Android NDK root (r28+)
  UGK_TERMINAL_VENDOR_DIR   Persistent source/build cache outside the Git tree

Optional environment variables:
  UGK_TERMINAL_SQLITE_OUTPUT  Output .so path. Defaults to this repository's
                              ugk-terminal-runtime-android/src/main/jniLibs path.
  UGK_TERMINAL_OVERWRITE=1    Permit replacing an existing output payload.
  UGK_TERMINAL_JOBS=4         Parallel make job count.
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
    ;;
  x86_64)
    readonly TARGET_TRIPLE="x86_64-linux-android"
    ;;
  *)
    echo "Unsupported ABI: $TARGET_ABI" >&2
    usage >&2
    exit 64
    ;;
esac

: "${ANDROID_NDK_ROOT:?Set ANDROID_NDK_ROOT to the Android NDK root.}"
: "${UGK_TERMINAL_VENDOR_DIR:?Set UGK_TERMINAL_VENDOR_DIR to an external build cache.}"

to_posix_path() {
  local path="$1"
  if [[ "$(uname -s)" == MINGW* || "$(uname -s)" == MSYS* || "$(uname -s)" == CYGWIN* ]]; then
    cygpath -u "$path"
  else
    printf '%s\n' "$path"
  fi
}

readonly NDK_ROOT="$(to_posix_path "$ANDROID_NDK_ROOT")"
readonly VENDOR_DIR="$(to_posix_path "$UGK_TERMINAL_VENDOR_DIR")"
readonly REPOSITORY_ROOT="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
readonly DEFAULT_OUTPUT="$REPOSITORY_ROOT/ugk-terminal-runtime-android/src/main/jniLibs/$TARGET_ABI/libugk_sqlite3.so"
readonly OUTPUT_PATH="$(to_posix_path "${UGK_TERMINAL_SQLITE_OUTPUT:-$DEFAULT_OUTPUT}")"
readonly JOBS="${UGK_TERMINAL_JOBS:-4}"

case "$(uname -s)" in
  Linux)
    readonly NDK_HOST_TAG="linux-x86_64"
    readonly MAKE_FOR_BUILD="${MAKE_FOR_BUILD:-make}"
    readonly BUILD_TRIPLE="${BUILD_TRIPLE:-x86_64-pc-linux-gnu}"
    ;;
  Darwin)
    if [[ "$(uname -m)" == "arm64" ]]; then
      readonly NDK_HOST_TAG="darwin-arm64"
    else
      readonly NDK_HOST_TAG="darwin-x86_64"
    fi
    readonly MAKE_FOR_BUILD="${MAKE_FOR_BUILD:-make}"
    readonly BUILD_TRIPLE="${BUILD_TRIPLE:-x86_64-apple-darwin}"
    ;;
  MINGW*|MSYS*|CYGWIN*)
    readonly NDK_HOST_TAG="windows-x86_64"
    readonly MAKE_FOR_BUILD="${MAKE_FOR_BUILD:-mingw32-make}"
    readonly BUILD_TRIPLE="${BUILD_TRIPLE:-x86_64-w64-mingw32}"
    ;;
  *)
    echo "Unsupported build host: $(uname -s)" >&2
    exit 64
    ;;
esac

readonly TOOLCHAIN_BIN="$NDK_ROOT/toolchains/llvm/prebuilt/$NDK_HOST_TAG/bin"
readonly CLANG="$TOOLCHAIN_BIN/$TARGET_TRIPLE"24-clang
readonly ARCHIVE_DIR="$VENDOR_DIR/sources"
readonly ARCHIVE_PATH="$ARCHIVE_DIR/$SQLITE_ARCHIVE"
readonly WORK_DIR="$VENDOR_DIR/build/sqlite-$SQLITE_VERSION-$TARGET_ABI-$(date +%Y%m%d%H%M%S)-$$"

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Required command is missing: $1" >&2
    exit 69
  fi
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

download_if_missing() {
  local url="$1"
  local output="$2"
  local temporary_output="$output.tmp.$$"
  mkdir -p "$(dirname -- "$output")"
  if [[ -f "$output" ]]; then
    return
  fi
  curl --fail --location --retry 3 --output "$temporary_output" "$url"
  mv -f "$temporary_output" "$output"
}

require_command curl
require_command sha256sum
require_command unzip
require_command "$MAKE_FOR_BUILD"

if [[ ! -x "$CLANG" ]]; then
  echo "Android target compiler is missing: $CLANG" >&2
  exit 66
fi
if [[ ! -x "$TOOLCHAIN_BIN/llvm-strip" ]]; then
  echo "Android llvm-strip is missing: $TOOLCHAIN_BIN/llvm-strip" >&2
  exit 66
fi
if [[ -e "$OUTPUT_PATH" && "${UGK_TERMINAL_OVERWRITE:-0}" != "1" ]]; then
  echo "Refusing to overwrite existing payload: $OUTPUT_PATH" >&2
  exit 73
fi

download_if_missing "$SQLITE_SOURCE_URL" "$ARCHIVE_PATH"
verify_sha256 "$ARCHIVE_PATH" "$SQLITE_ARCHIVE_SHA256"

mkdir -p "$WORK_DIR"
unzip -q "$ARCHIVE_PATH" -d "$WORK_DIR"
cd "$WORK_DIR/sqlite-src-$SQLITE_SOURCE_ID"

# The CLI is intentionally non-interactive and cannot load arbitrary native
# SQLite extensions from writable storage. sqlite3's configure probes miss
# libm while cross compiling, so the make-time override is explicit.
env \
  CC="$CLANG" \
  AR="$TOOLCHAIN_BIN/llvm-ar" \
  LD="$TOOLCHAIN_BIN/ld.lld" \
  RANLIB="$TOOLCHAIN_BIN/llvm-ranlib" \
  STRIP="$TOOLCHAIN_BIN/llvm-strip" \
  CFLAGS='-O2 -fPIE -DSQLITE_OMIT_LOAD_EXTENSION=1 -DSQLITE_DEFAULT_MEMSTATUS=0' \
  LDFLAGS='-fPIE -pie -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 -Wl,-z,relro,-z,now' \
  ./configure \
    --build="$BUILD_TRIPLE" \
    --host="$TARGET_TRIPLE" \
    --prefix=/ugk-terminal-prefix \
    --disable-shared \
    --disable-readline \
    --disable-tcl \
    --disable-load-extension

"$MAKE_FOR_BUILD" -j"$JOBS" LDFLAGS.math=-lm LDFLAGS.rpath= sqlite3
"$TOOLCHAIN_BIN/llvm-strip" --strip-unneeded sqlite3

mkdir -p "$(dirname -- "$OUTPUT_PATH")"
temporary_output="$OUTPUT_PATH.tmp.$$"
cp sqlite3 "$temporary_output"
mv -f "$temporary_output" "$OUTPUT_PATH"

echo "SQLite payload: $OUTPUT_PATH"
"$TOOLCHAIN_BIN/llvm-readelf" -h -l -d "$OUTPUT_PATH"
