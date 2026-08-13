#!/usr/bin/env bash
# Builds a small, relocatable HTTPS command profile for the UGK Android
# terminal runtime: OpenSSL CLI, curl CLI and a locked CA bundle.
#
# Native payloads deliberately keep .so file names so Android's package manager
# extracts them into nativeLibraryDir. The Runtime maps the logical curl and
# openssl commands to those immutable files; it never executes downloaded ELF.
set -euo pipefail

readonly OPENSSL_VERSION="3.6.3"
readonly OPENSSL_ARCHIVE="openssl-$OPENSSL_VERSION.tar.gz"
readonly OPENSSL_URL="https://github.com/openssl/openssl/releases/download/openssl-$OPENSSL_VERSION/$OPENSSL_ARCHIVE"
readonly OPENSSL_SHA256="243a86649cf6f23eeb6a2ff2456e09e5d77dd9018a54d3d96b0c6bdd6ba6c7f1"

readonly CURL_VERSION="8.21.0"
readonly CURL_ARCHIVE="curl-$CURL_VERSION.tar.xz"
readonly CURL_URL="https://github.com/curl/curl/releases/download/curl-8_21_0/$CURL_ARCHIVE"
readonly CURL_SHA256="aa1b66a70eace83dc624508745646c08ae561de512ab403adffb93ac87fc72e6"

readonly CA_BUNDLE_VERSION="2026-07-16"
readonly CA_BUNDLE_ARCHIVE="cacert-$CA_BUNDLE_VERSION.pem"
readonly CA_BUNDLE_URL="https://curl.se/ca/$CA_BUNDLE_ARCHIVE"
readonly CA_BUNDLE_SHA256="3ff344e30b9b1ed2971044eabb438a08f2e2245ddb5f8ab1a3ad8b63ab4eaf91"
readonly RUNTIME_PREFIX="/ugk-terminal-prefix"

usage() {
  cat <<'EOF'
Usage: build-network-runtime.sh <arm64-v8a|x86_64>

Required environment variables:
  ANDROID_NDK_ROOT                    Android NDK root (r28+)
  UGK_TERMINAL_VENDOR_DIR             Persistent source/build cache outside the Git tree

Optional environment variables:
  UGK_TERMINAL_NETWORK_OUTPUT_DIR     Directory for libugk_openssl.so and libugk_curl.so.
                                      Defaults to the Runtime module JNI directory for the ABI.
  UGK_TERMINAL_CA_OUTPUT              Path for cert.pem. Defaults to the Runtime module asset.
  UGK_TERMINAL_OVERWRITE=1            Permit replacing an existing binary or mismatched CA asset.
  UGK_TERMINAL_JOBS=4                 Parallel make job count.

The curl profile intentionally supports only file/http/https and statically
links OpenSSL. HTTP/2, HTTP/3, SSH, LDAP, FTP, mail protocols, HSTS, Alt-Svc,
WebSocket, libpsl, brotli and zstd are excluded from this first network slice.
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
    readonly OPENSSL_TARGET="android-arm64"
    ;;
  x86_64)
    readonly TARGET_TRIPLE="x86_64-linux-android"
    readonly OPENSSL_TARGET="android-x86_64"
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

to_compiler_path() {
  local path="$1"
  if [[ "$HOST_NEEDS_PERL_SHIMS" == true ]]; then
    cygpath -m "$path"
  else
    printf '%s\n' "$path"
  fi
}

readonly NDK_ROOT="$(to_posix_path "$ANDROID_NDK_ROOT")"
readonly VENDOR_DIR="$(to_posix_path "$UGK_TERMINAL_VENDOR_DIR")"
readonly REPOSITORY_ROOT="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
readonly DEFAULT_OUTPUT_DIR="$REPOSITORY_ROOT/ugk-terminal-runtime-android/src/main/jniLibs/$TARGET_ABI"
readonly OUTPUT_DIR="$(to_posix_path "${UGK_TERMINAL_NETWORK_OUTPUT_DIR:-$DEFAULT_OUTPUT_DIR}")"
readonly DEFAULT_CA_OUTPUT="$REPOSITORY_ROOT/ugk-terminal-runtime-android/src/main/assets/ugk-terminal-runtime/cert.pem"
readonly CA_OUTPUT="$(to_posix_path "${UGK_TERMINAL_CA_OUTPUT:-$DEFAULT_CA_OUTPUT}")"
readonly OPENSSL_OUTPUT="$OUTPUT_DIR/libugk_openssl.so"
readonly CURL_OUTPUT="$OUTPUT_DIR/libugk_curl.so"
readonly JOBS="${UGK_TERMINAL_JOBS:-4}"

case "$(uname -s)" in
  Linux)
    readonly NDK_HOST_TAG="linux-x86_64"
    readonly MAKE_FOR_BUILD="${MAKE_FOR_BUILD:-make}"
    readonly BUILD_TRIPLE="${BUILD_TRIPLE:-x86_64-pc-linux-gnu}"
    readonly HOST_NEEDS_PERL_SHIMS=false
    ;;
  Darwin)
    if [[ "$(uname -m)" == arm64 ]]; then
      readonly NDK_HOST_TAG="darwin-arm64"
    else
      readonly NDK_HOST_TAG="darwin-x86_64"
    fi
    readonly MAKE_FOR_BUILD="${MAKE_FOR_BUILD:-make}"
    readonly BUILD_TRIPLE="${BUILD_TRIPLE:-x86_64-apple-darwin}"
    readonly HOST_NEEDS_PERL_SHIMS=false
    ;;
  MINGW*|MSYS*|CYGWIN*)
    readonly NDK_HOST_TAG="windows-x86_64"
    readonly MAKE_FOR_BUILD="${MAKE_FOR_BUILD:-mingw32-make}"
    readonly BUILD_TRIPLE="${BUILD_TRIPLE:-x86_64-w64-mingw32}"
    readonly HOST_NEEDS_PERL_SHIMS=true
    ;;
  *)
    echo "Unsupported build host: $(uname -s)" >&2
    exit 64
    ;;
esac

readonly TOOLCHAIN_BIN="$NDK_ROOT/toolchains/llvm/prebuilt/$NDK_HOST_TAG/bin"
readonly CLANG="$TOOLCHAIN_BIN/$TARGET_TRIPLE"24-clang
readonly ARCHIVE_DIR="$VENDOR_DIR/sources"
readonly OPENSSL_ARCHIVE_PATH="$ARCHIVE_DIR/$OPENSSL_ARCHIVE"
readonly CURL_ARCHIVE_PATH="$ARCHIVE_DIR/$CURL_ARCHIVE"
readonly CA_BUNDLE_PATH="$ARCHIVE_DIR/$CA_BUNDLE_ARCHIVE"
readonly WORK_DIR="$VENDOR_DIR/build/network-$OPENSSL_VERSION-$CURL_VERSION-$TARGET_ABI-$(date +%Y%m%d%H%M%S)-$$"
readonly OPENSSL_SOURCE_DIR="$WORK_DIR/openssl-$OPENSSL_VERSION"
readonly CURL_SOURCE_DIR="$WORK_DIR/curl-$CURL_VERSION"
readonly WINDOWS_PERL_SHIMS="$REPOSITORY_ROOT/scripts/terminal-runtime/windows-perl-shims"

# OpenSSL derives the target compiler name from its Android target profile;
# unlike curl it does not receive CC directly, so the NDK wrappers must be on
# PATH during Configure and make.  On Git for Windows its configuration code
# compares `which clang` with ANDROID_NDK_ROOT, so both values must use the
# same POSIX spelling rather than mixing `/e/...` with `E:\\...`.
export ANDROID_NDK_ROOT="$NDK_ROOT"
export PATH="$TOOLCHAIN_BIN:$PATH"

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

if [[ ! -x "$CLANG" ]]; then
  echo "Android clang is missing: $CLANG" >&2
  exit 66
fi
if [[ ! -x "$TOOLCHAIN_BIN/llvm-strip" ]]; then
  echo "Android llvm-strip is missing: $TOOLCHAIN_BIN/llvm-strip" >&2
  exit 66
fi
require_command curl
require_command perl
require_command tar
require_command sha256sum
require_command "$MAKE_FOR_BUILD"

if [[ "$HOST_NEEDS_PERL_SHIMS" == true && ! -d "$WINDOWS_PERL_SHIMS" ]]; then
  echo "Git for Windows Perl shims are missing: $WINDOWS_PERL_SHIMS" >&2
  exit 66
fi

for output in "$OPENSSL_OUTPUT" "$CURL_OUTPUT"; do
  if [[ -e "$output" && "${UGK_TERMINAL_OVERWRITE:-0}" != 1 ]]; then
    echo "Refusing to overwrite existing payload: $output" >&2
    exit 73
  fi
done
if [[ -e "$CA_OUTPUT" && "$(sha256sum "$CA_OUTPUT" | awk '{print $1}')" != "$CA_BUNDLE_SHA256" && "${UGK_TERMINAL_OVERWRITE:-0}" != 1 ]]; then
  echo "Refusing to overwrite mismatched CA bundle: $CA_OUTPUT" >&2
  exit 73
fi

download_if_missing "$OPENSSL_URL" "$OPENSSL_ARCHIVE_PATH"
download_if_missing "$CURL_URL" "$CURL_ARCHIVE_PATH"
download_if_missing "$CA_BUNDLE_URL" "$CA_BUNDLE_PATH"
verify_sha256 "$OPENSSL_ARCHIVE_PATH" "$OPENSSL_SHA256"
verify_sha256 "$CURL_ARCHIVE_PATH" "$CURL_SHA256"
verify_sha256 "$CA_BUNDLE_PATH" "$CA_BUNDLE_SHA256"

mkdir -p "$WORK_DIR"
tar -xf "$OPENSSL_ARCHIVE_PATH" -C "$WORK_DIR"
tar -xf "$CURL_ARCHIVE_PATH" -C "$WORK_DIR"

(
  cd "$OPENSSL_SOURCE_DIR"
  if [[ "$HOST_NEEDS_PERL_SHIMS" == true ]]; then
    export PERL5LIB="$WINDOWS_PERL_SHIMS${PERL5LIB:+:$PERL5LIB}"
  fi
  ./Configure "$OPENSSL_TARGET" \
    -D__ANDROID_API__=24 \
    no-shared \
    no-tests \
    no-module \
    no-legacy \
    no-engine \
    no-dso \
    no-ssl \
    --prefix="$RUNTIME_PREFIX" \
    --openssldir="$RUNTIME_PREFIX/etc/tls"

  if [[ "$HOST_NEEDS_PERL_SHIMS" == true ]]; then
    MSYS_NO_PATHCONV=1 "$MAKE_FOR_BUILD" -j"$JOBS" build_sw
  else
    "$MAKE_FOR_BUILD" -j"$JOBS" build_sw
  fi
  "$TOOLCHAIN_BIN/llvm-strip" --strip-unneeded apps/openssl
)

readonly OPENSSL_COMPILER_PATH="$(to_compiler_path "$OPENSSL_SOURCE_DIR")"
(
  cd "$CURL_SOURCE_DIR"
  env \
    CC="$CLANG" \
    AR="$TOOLCHAIN_BIN/llvm-ar" \
    RANLIB="$TOOLCHAIN_BIN/llvm-ranlib" \
    STRIP="$TOOLCHAIN_BIN/llvm-strip" \
    MAKE="$MAKE_FOR_BUILD" \
    CFLAGS='-O2 -fPIE' \
    CPPFLAGS="-I$OPENSSL_COMPILER_PATH/include" \
    LDFLAGS="-L$OPENSSL_COMPILER_PATH -fPIE -pie -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 -Wl,-z,relro,-z,now" \
    LIBS='-ldl -pthread' \
    ./configure \
      --build="$BUILD_TRIPLE" \
      --host="$TARGET_TRIPLE" \
      --prefix="$RUNTIME_PREFIX" \
      --disable-dependency-tracking \
      --disable-shared \
      --enable-static \
      --with-openssl="$OPENSSL_COMPILER_PATH" \
      --with-ca-bundle="$RUNTIME_PREFIX/etc/tls/cert.pem" \
      --without-ca-path \
      --without-brotli \
      --without-zstd \
      --without-libpsl \
      --without-libssh2 \
      --without-nghttp2 \
      --without-nghttp3 \
      --without-ngtcp2 \
      --without-libidn2 \
      --disable-ldap \
      --disable-ldaps \
      --disable-ipfs \
      --disable-websockets \
      --disable-alt-svc \
      --disable-hsts \
      --disable-tls-srp \
      --disable-unix-sockets \
      --disable-ares \
      --disable-rtsp \
      --disable-dict \
      --disable-gopher \
      --disable-imap \
      --disable-mqtt \
      --disable-pop3 \
      --disable-smb \
      --disable-smtp \
      --disable-telnet \
      --disable-tftp \
      --disable-ftp \
      --disable-manual \
      --disable-docs

  if [[ "$HOST_NEEDS_PERL_SHIMS" == true ]]; then
    MSYS_NO_PATHCONV=1 "$MAKE_FOR_BUILD" -j"$JOBS"
  else
    "$MAKE_FOR_BUILD" -j"$JOBS"
  fi
  "$TOOLCHAIN_BIN/llvm-strip" --strip-unneeded src/curl
)

publish_file "$OPENSSL_SOURCE_DIR/apps/openssl" "$OPENSSL_OUTPUT"
publish_file "$CURL_SOURCE_DIR/src/curl" "$CURL_OUTPUT"
publish_file "$CA_BUNDLE_PATH" "$CA_OUTPUT"

echo "OpenSSL payload: $OPENSSL_OUTPUT"
"$TOOLCHAIN_BIN/llvm-readelf" -h -l -d "$OPENSSL_OUTPUT"
echo "curl payload: $CURL_OUTPUT"
"$TOOLCHAIN_BIN/llvm-readelf" -h -l -d "$CURL_OUTPUT"
echo "CA bundle: $CA_OUTPUT"
verify_sha256 "$CA_OUTPUT" "$CA_BUNDLE_SHA256"
