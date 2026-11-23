#!/usr/bin/env bash
set -euo pipefail

# Nova VPN Native Cross-Compilation Script
# Targets: arm64-v8a, armeabi-v7a, x86_64
# Produces:
#   app/src/main/jniLibs/<abi>/libaether.so
#   app/src/main/jniLibs/<abi>/libpsiphon.so
#   app/src/main/jniLibs/<abi>/liblyrebird.so
#   app/src/main/jniLibs/<abi>/libhev-socks5-tunnel.so
#   app/libs/libbox.aar

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
NATIVE_SRC_DIR="${ROOT_DIR}/native-src"
JNI_OUT="${ROOT_DIR}/app/src/main/jniLibs"
LIBS_OUT="${ROOT_DIR}/app/libs"
API_LEVEL=26

NDK_HOME="${ANDROID_NDK_HOME:-${ANDROID_SDK_ROOT:-}/ndk-bundle}"
if [[ ! -d "${NDK_HOME}/toolchains" ]] && [[ -n "${ANDROID_SDK_ROOT:-}" ]] && [[ -d "${ANDROID_SDK_ROOT}/ndk" ]]; then
  NDK_HOME="$(find "${ANDROID_SDK_ROOT}/ndk" -maxdepth 1 -mindepth 1 | sort -V | tail -n 1)"
fi

if [[ ! -d "${NDK_HOME}/toolchains" ]]; then
  echo "ERROR: Android NDK not found. Set ANDROID_NDK_HOME." >&2
  exit 1
fi

HOST_TAG="$(uname -s | tr '[:upper:]' '[:lower:]')-$(uname -m)"
TOOLCHAIN="${NDK_HOME}/toolchains/llvm/prebuilt/${HOST_TAG}"
if [[ ! -d "${TOOLCHAIN}" ]]; then
  TOOLCHAIN="$(find "${NDK_HOME}/toolchains/llvm/prebuilt" -maxdepth 1 -mindepth 1 | head -n 1)"
fi

mkdir -p "${JNI_OUT}/arm64-v8a" "${JNI_OUT}/armeabi-v7a" "${JNI_OUT}/x86_64" "${LIBS_OUT}" "${NATIVE_SRC_DIR}"

build_go_bin() {
  local src_dir="$1"
  local pkg_path="$2"
  local out_name="$3"

  if [[ ! -d "${src_dir}" ]]; then
    echo "WARN: ${src_dir} not found; skipping ${out_name}."
    return 0
  fi

  local abis=("arm64-v8a" "armeabi-v7a" "x86_64")
  for abi in "${abis[@]}"; do
    local goarch="" goarm="" cc=""
    case "${abi}" in
      arm64-v8a)
        goarch="arm64"
        cc="${TOOLCHAIN}/bin/aarch64-linux-android${API_LEVEL}-clang"
        ;;
      armeabi-v7a)
        goarch="arm"
        goarm="7"
        cc="${TOOLCHAIN}/bin/armv7a-linux-androideabi${API_LEVEL}-clang"
        ;;
      x86_64)
        goarch="amd64"
        cc="${TOOLCHAIN}/bin/x86_64-linux-android${API_LEVEL}-clang"
        ;;
    esac

    echo "==> Building ${out_name} for ${abi}"
    (
      cd "${src_dir}"
      CGO_ENABLED=1 \
      GOOS=android \
      GOARCH="${goarch}" \
      GOARM="${goarm}" \
      CC="${cc}" \
      go build -trimpath -ldflags="-s -w -buildid=" -o "${JNI_OUT}/${abi}/${out_name}" "${pkg_path}"
    )
  done
}

# 1. Aether core (MASQUE / WireGuard / obfuscation)
build_go_bin "${NATIVE_SRC_DIR}/aether" "./cmd/aether" "libaether.so"

# 2. Optional Psiphon tunnel core
build_go_bin "${NATIVE_SRC_DIR}/psiphon" "./MobileLibrary/psi" "libpsiphon.so"

# 3. Tor / Lyrebird pluggable transport binary
build_go_bin "${NATIVE_SRC_DIR}/lyrebird" "./cmd/lyrebird" "liblyrebird.so"

# 4. hev-socks5-tunnel (C NDK build fallback TUN engine)
if [[ -d "${NATIVE_SRC_DIR}/hev-socks5-tunnel" ]]; then
  echo "==> Building libhev-socks5-tunnel.so via ndk-build"
  "${NDK_HOME}/ndk-build" \
    NDK_PROJECT_PATH="${NATIVE_SRC_DIR}/hev-socks5-tunnel" \
    APP_BUILD_SCRIPT="${NATIVE_SRC_DIR}/hev-socks5-tunnel/Android.mk" \
    APP_ABI="arm64-v8a armeabi-v7a x86_64" \
    APP_PLATFORM="android-${API_LEVEL}" \
    NDK_LIBS_OUT="${JNI_OUT}"
fi

# 5. sing-box libbox.aar (default gVisor TUN engine)
if [[ -d "${NATIVE_SRC_DIR}/sing-box" ]] && command -v gomobile >/dev/null 2>&1; then
  echo "==> Building libbox.aar with gomobile"
  (
    cd "${NATIVE_SRC_DIR}/sing-box"
    ANDROID_NDK_HOME="${NDK_HOME}" \
    gomobile bind -v -androidapi "${API_LEVEL}" \
      -target="android/arm64,android/arm,android/amd64" \
      -tags="with_gvisor,with_utls" \
      -o "${LIBS_OUT}/libbox.aar" \
      ./experimental/libbox
  )
else
  echo "INFO: sing-box source or gomobile not found; skipping libbox.aar."
fi

echo "Native build script finished."
