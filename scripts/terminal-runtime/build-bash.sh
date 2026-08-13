#!/usr/bin/env bash
# Builds a relocatable Bash payload for the UGK Android terminal runtime.
#
# The resulting ELF is named as an Android native library so Gradle packages it
# from src/main/jniLibs into the APK's native-library directory. Android can
# execute that extracted path even when the target SDK forbids execve() from
# an app-writable directory.
set -euo pipefail

readonly BASH_MAJOR_VERSION="5.3"
readonly BASH_VERSION="5.3.15"
readonly BASH_PATCH_SERIES="53"
readonly BASH_ARCHIVE_SHA256="0d5cd86965f869a26cf64f4b71be7b96f90a3ba8b3d74e27e8e9d9d5550f31ba"

usage() {
  cat <<'EOF'
Usage: build-bash.sh <arm64-v8a|x86_64>

Required environment variables:
  ANDROID_NDK_ROOT          Android NDK root (r28+)
  UGK_TERMINAL_VENDOR_DIR   Persistent source/build cache outside the Git tree

Optional environment variables:
  UGK_TERMINAL_BASH_OUTPUT  Output .so path. Defaults to this repository's
                            ugk-terminal-runtime-android/src/main/jniLibs path.
  UGK_TERMINAL_OVERWRITE=1  Permit replacing an existing output payload.
  UGK_TERMINAL_JOBS=4       Parallel make job count.
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
readonly DEFAULT_OUTPUT="$REPOSITORY_ROOT/ugk-terminal-runtime-android/src/main/jniLibs/$TARGET_ABI/libugk_bash.so"
readonly OUTPUT_PATH="$(to_posix_path "${UGK_TERMINAL_BASH_OUTPUT:-$DEFAULT_OUTPUT}")"
readonly JOBS="${UGK_TERMINAL_JOBS:-4}"

case "$(uname -s)" in
  Linux)
    readonly NDK_HOST_TAG="linux-x86_64"
    readonly MAKE_FOR_BUILD="${MAKE_FOR_BUILD:-make}"
    readonly CC_FOR_BUILD="${CC_FOR_BUILD:-cc}"
    readonly BUILD_TRIPLE="${BUILD_TRIPLE:-x86_64-pc-linux-gnu}"
    readonly HOST_NEEDS_COMPAT_SHIM=false
    ;;
  Darwin)
    if [[ "$(uname -m)" == "arm64" ]]; then
      readonly NDK_HOST_TAG="darwin-arm64"
    else
      readonly NDK_HOST_TAG="darwin-x86_64"
    fi
    readonly MAKE_FOR_BUILD="${MAKE_FOR_BUILD:-make}"
    readonly CC_FOR_BUILD="${CC_FOR_BUILD:-cc}"
    readonly BUILD_TRIPLE="${BUILD_TRIPLE:-x86_64-apple-darwin}"
    readonly HOST_NEEDS_COMPAT_SHIM=false
    ;;
  MINGW*|MSYS*|CYGWIN*)
    readonly NDK_HOST_TAG="windows-x86_64"
    readonly MAKE_FOR_BUILD="${MAKE_FOR_BUILD:-mingw32-make}"
    readonly CC_FOR_BUILD="${CC_FOR_BUILD:-gcc}"
    readonly BUILD_TRIPLE="${BUILD_TRIPLE:-x86_64-w64-mingw32}"
    readonly HOST_NEEDS_COMPAT_SHIM=true
    ;;
  *)
    echo "Unsupported build host: $(uname -s)" >&2
    exit 64
    ;;
esac

readonly TOOLCHAIN_BIN="$NDK_ROOT/toolchains/llvm/prebuilt/$NDK_HOST_TAG/bin"
readonly CLANG="$TOOLCHAIN_BIN/$TARGET_TRIPLE"24-clang
readonly ARCHIVE_DIR="$VENDOR_DIR/sources"
readonly PATCH_DIR="$ARCHIVE_DIR/bash-$BASH_MAJOR_VERSION-patches"
readonly ARCHIVE_PATH="$ARCHIVE_DIR/bash-$BASH_MAJOR_VERSION.tar.gz"
readonly WORK_DIR="$VENDOR_DIR/build/bash-$BASH_VERSION-$TARGET_ABI-$(date +%Y%m%d%H%M%S)-$$"
readonly SOURCE_DIR="$WORK_DIR/bash-$BASH_MAJOR_VERSION"
readonly TARGET_COMPAT_HEADER="$WORK_DIR/ugk-bionic-api24-compat.h"
if [[ "$HOST_NEEDS_COMPAT_SHIM" == true ]]; then
  readonly HOST_COMPAT_CFLAGS="-std=gnu17"
else
  readonly HOST_COMPAT_CFLAGS=""
fi

declare -A PATCH_SHA256=(
  [001]=1f608434364af86b9b45c8b0ea3fb3b165fb830d27697e6cdfc7ac17dee3287f
  [002]=e385548a00130765ec7938a56fbdca52447ab41fabc95a25f19ade527e282001
  [003]=f245d9c7dc3f5a20d84b53d249334747940936f09dc97e1dcb89fc3ab37d60ed
  [004]=9591d245045529f32f0812f94180b9d9ce9023f5a765c039b852e5dfc99747d0
  [005]=cca1ef52dbbf433bc98e33269b64b2c814028efe2538be1e2c9a377da90bc99d
  [006]=29119addefed8eff91ae37fd51822c31780ee30d4a28376e96002706c995ff10
  [007]=c0976bbfffa1453c7cfdd62058f206a318568ff2d690f5d4fa048793fa3eb299
  [008]=097cd723cbfb8907674ac32214063a3fd85282657ec5b4e544d2c0f719653fb4
  [009]=eee30fe78a4b0cb2fe20e010e00308899cfc613e0774ebb3c8557a1552f24f8c
  [010]=cf76f1cce2ea300c18bff9f002d21f280cc931acd17c28518110b93fe6e72569
  [011]=0298df8f5ea2a31d3be43ed7d269c5b3c7c342dd5b570bea7f64d66dcbbe7531
  [012]=d71379b39bebaedaf123414414e77fb458a0a43b9ad3116594c6df7ca6754573
  [013]=042f9cda967e24bf4211944697441e93d06ff42b4b998629a98a1b249279f200
  [014]=bd4360b401d38507e358783dcad8536a99c6789f0d3a5bd0cfb8c4a34144696c
  [015]=55b79ceee2fc27f6767eed697e939a7eb2fe2a28c01556bd75f18d581014f46e
)

download_if_missing() {
  local url="$1"
  local path="$2"
  local temporary_path
  if [[ -f "$path" ]]; then
    return
  fi
  mkdir -p "$(dirname -- "$path")"
  temporary_path="$path.partial.$$"
  curl --fail --location --retry 3 --output "$temporary_path" "$url"
  mv -f "$temporary_path" "$path"
}

verify_sha256() {
  local path="$1"
  local expected="$2"
  printf '%s  %s\n' "$expected" "$path" | sha256sum --check --status -
}

if [[ ! -x "$CLANG" ]]; then
  echo "Android clang is missing: $CLANG" >&2
  exit 66
fi
if ! command -v "$MAKE_FOR_BUILD" >/dev/null 2>&1; then
  echo "Build make is missing: $MAKE_FOR_BUILD" >&2
  exit 66
fi
if ! command -v "$CC_FOR_BUILD" >/dev/null 2>&1; then
  echo "Host compiler is missing: $CC_FOR_BUILD" >&2
  exit 66
fi
if [[ -e "$OUTPUT_PATH" && "${UGK_TERMINAL_OVERWRITE:-0}" != "1" ]]; then
  echo "Refusing to overwrite existing payload: $OUTPUT_PATH" >&2
  exit 73
fi

download_if_missing "https://mirrors.kernel.org/gnu/bash/bash-$BASH_MAJOR_VERSION.tar.gz" "$ARCHIVE_PATH"
verify_sha256 "$ARCHIVE_PATH" "$BASH_ARCHIVE_SHA256"

for patch_number in $(seq -w 1 15); do
  patch_number="0$patch_number"
  patch_path="$PATCH_DIR/bash$BASH_PATCH_SERIES-$patch_number"
  download_if_missing "https://mirrors.kernel.org/gnu/bash/bash-$BASH_MAJOR_VERSION-patches/bash$BASH_PATCH_SERIES-$patch_number" "$patch_path"
  verify_sha256 "$patch_path" "${PATCH_SHA256[$patch_number]}"
done

mkdir -p "$WORK_DIR"
tar -xzf "$ARCHIVE_PATH" -C "$WORK_DIR"
cd "$SOURCE_DIR"

cat > "$TARGET_COMPAT_HEADER" <<'EOF'
/*
 * API-24 compatibility shims modelled after Termux's NDK header patches.
 * This header is passed only to Android target compilation, never to host
 * helper programs. Group enumeration is intentionally a no-op on Android;
 * there is no meaningful /etc/group database to enumerate in an app sandbox.
 */
#ifndef UGK_BIONIC_API24_COMPAT_H
#define UGK_BIONIC_API24_COMPAT_H

#include <grp.h>
#include <stdlib.h>

#if defined(__ANDROID_API__) && __ANDROID_API__ < 26
#define getgrent ugk_bionic_getgrent
#define setgrent ugk_bionic_setgrent
#define endgrent ugk_bionic_endgrent
static inline struct group* ugk_bionic_getgrent(void) { return 0; }
static inline void ugk_bionic_setgrent(void) {}
static inline void ugk_bionic_endgrent(void) {}

#define mblen ugk_bionic_mblen
static inline int ugk_bionic_mblen(const char* value, size_t size) {
  return value == 0 ? 0 : mbtowc(0, value, size);
}

#undef MB_CUR_MAX
#define MB_CUR_MAX 4
#endif

/* The Windows NDK wrapper strips literal quotes from these -D values. */
#undef PROGRAM
#define PROGRAM "bash"
#undef PACKAGE
#define PACKAGE "bash"
#undef LOCALEDIR
#define LOCALEDIR "ugk-terminal-prefix/share/locale"
#undef CONF_HOSTTYPE
#define CONF_HOSTTYPE "android"
#undef CONF_OSTYPE
#define CONF_OSTYPE "android"
#undef CONF_MACHTYPE
#define CONF_MACHTYPE "android"
#undef CONF_VENDOR
#define CONF_VENDOR "ugk"
#endif
EOF

if [[ "$HOST_NEEDS_COMPAT_SHIM" == true ]]; then
  # mkbuiltins is a Windows-host utility only. MinGW declares mkdir() with a
  # single argument while the Unix source supplies a POSIX mode. Patch that
  # one host-side call instead of changing Android target sources or headers.
  perl -0pi -e 's{^(\s*)i = mkdir \("helpfiles", 0777\);}{$1#ifdef _WIN32\n$1i = mkdir ("helpfiles");\n$1#else\n$1i = mkdir ("helpfiles", 0777);\n$1#endif}m' builtins/mkbuiltins.c
fi

for patch_number in $(seq -w 1 15); do
  patch_number="0$patch_number"
  patch -p0 -i "$PATCH_DIR/bash$BASH_PATCH_SERIES-$patch_number"
done

# This runtime is deliberately non-interactive. Keeping Bash job control
# enabled makes it issue terminal ioctls against stdout/stderr pipes, which
# Android SELinux denies inside an app process.
env \
  CC="$CLANG" \
  AR="$TOOLCHAIN_BIN/llvm-ar" \
  AS="$CLANG" \
  LD="$TOOLCHAIN_BIN/ld.lld" \
  NM="$TOOLCHAIN_BIN/llvm-nm" \
  RANLIB="$TOOLCHAIN_BIN/llvm-ranlib" \
  STRIP="$TOOLCHAIN_BIN/llvm-strip" \
  CC_FOR_BUILD="$CC_FOR_BUILD" \
  CFLAGS_FOR_BUILD="-g -DCROSS_COMPILING $HOST_COMPAT_CFLAGS" \
  MAKE="$MAKE_FOR_BUILD" \
  CFLAGS="-O2 -fPIE -include $TARGET_COMPAT_HEADER" \
  LDFLAGS='-fPIE -pie -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 -Wl,-z,relro,-z,now' \
  LIBS='-lm' \
  bash_cv_job_control_missing=present \
  bash_cv_sys_siglist=yes \
  bash_cv_func_sigsetjmp=present \
  bash_cv_unusable_rtsigs=no \
  ac_cv_func_mbsnrtowcs=no \
  ac_cv_func_dprintf=yes \
  ac_cv_func_fpurge=yes \
  ac_cv_func___fpurge=no \
  ac_cv_have_decl_fpurge=yes \
  bash_cv_dev_fd=whacky \
  bash_cv_getcwd_malloc=yes \
  ./configure \
    --build="$BUILD_TRIPLE" \
    --host="$TARGET_TRIPLE" \
    --prefix=/ugk-terminal-prefix \
    --disable-nls \
    --disable-job-control \
    --enable-multibyte \
    --without-bash-malloc \
    --disable-profiling

if [[ "$HOST_NEEDS_COMPAT_SHIM" == true ]]; then
  # configure derives -rdynamic from the Android target host, then incorrectly
  # reuses it while linking MinGW-only helper binaries. The Android ELF still
  # keeps its target LDFLAGS; only the helpers lose this Linux linker flag.
  perl -pi -e 's/^LDFLAGS_FOR_BUILD =.*$/LDFLAGS_FOR_BUILD = \$(CFLAGS_FOR_BUILD)/' Makefile builtins/Makefile

  # Autoconf records the MSYS-style absolute build directory (/e/...) in every
  # recursive Makefile. Git Bash understands that spelling, but mingw32-make
  # does not when it enters subdirectories. Convert only generated Makefile
  # paths to the Windows form (E:/...) after configure; target compiler flags
  # and the source archive remain otherwise unchanged.
  export UGK_POSIX_BUILD_DIR="$WORK_DIR"
  export UGK_WINDOWS_BUILD_DIR="$(cygpath -m "$WORK_DIR")"
  find . -name Makefile -type f -exec \
    perl -0pi -e 's/\Q$ENV{UGK_POSIX_BUILD_DIR}\E/$ENV{UGK_WINDOWS_BUILD_DIR}/g' {} +
  unset UGK_POSIX_BUILD_DIR UGK_WINDOWS_BUILD_DIR
fi

# psize.aux is another host helper whose value is not important to an
# app-private non-interactive runtime. Avoid compiling it with target config
# headers; Bash's own fallback value is intentionally conservative.
cat > builtins/pipesize.h <<'EOF'
/* Pre-seeded for cross compilation; see builtins/psize.sh. */
#define PIPESIZE 512
EOF
perl -pi -e 's/^pipesize\.h:.*$/pipesize.h:/' builtins/Makefile

"$MAKE_FOR_BUILD" -j"$JOBS" bash
"$TOOLCHAIN_BIN/llvm-strip" --strip-unneeded bash

mkdir -p "$(dirname -- "$OUTPUT_PATH")"
temporary_output="$OUTPUT_PATH.tmp.$$"
cp bash "$temporary_output"
mv -f "$temporary_output" "$OUTPUT_PATH"

echo "Bash payload: $OUTPUT_PATH"
"$TOOLCHAIN_BIN/llvm-readelf" -h -l -d "$OUTPUT_PATH"
