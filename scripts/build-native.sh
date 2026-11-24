#!/usr/bin/env bash
set -euo pipefail

# Nova VPN Native Release Sync Script
# Fetches and verifies prebuilt Android artifacts from upstream releases:
#   1. CluvexStudio/Aether (v2.4.0):
#      - app/src/main/jniLibs/<abi>/libaether.so   (Aether core + built-in Arti Tor)
#      - app/src/main/jniLibs/<abi>/libpsiphon.so  (psiphon-tunnel-core PT/tunnel)
#      - app/src/main/jniLibs/<abi>/liblyrebird.so (lyrebird PT for Tor obfs4/webtunnel/snowflake)
#   2. heiher/hev-socks5-tunnel (2.20.0):
#      - app/libs/hev-socks5-tunnel.aar            (TUN -> SOCKS5 engine + hev.htproxy.TProxyService)

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JNI_OUT="${ROOT_DIR}/app/src/main/jniLibs"
LIBS_OUT="${ROOT_DIR}/app/libs"

AETHER_TAG="${AETHER_TAG:-v2.4.0}"
AETHER_BASE="https://github.com/CluvexStudio/Aether/releases/download/${AETHER_TAG}"
HEV_TAG="${HEV_TAG:-2.20.0}"
HEV_AAR_URL="https://github.com/heiher/hev-socks5-tunnel/releases/download/${HEV_TAG}/hev-socks5-tunnel.aar"

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "${TMP_DIR}"' EXIT

mkdir -p "${JNI_OUT}/arm64-v8a" "${JNI_OUT}/armeabi-v7a" "${JNI_OUT}/x86_64" "${LIBS_OUT}"

echo "==> Fetching Aether ${AETHER_TAG} checksums..."
curl -fsSL -L "${AETHER_BASE}/SHA256SUMS.txt" -o "${TMP_DIR}/SHA256SUMS.txt"

sync_aether_abi() {
  local asset="$1"
  local abi="$2"

  echo "==> Downloading ${asset} (${abi})..."
  curl -fsSL -L "${AETHER_BASE}/${asset}" -o "${TMP_DIR}/${asset}"

  (
    cd "${TMP_DIR}"
    grep "  ${asset}\$" SHA256SUMS.txt | sha256sum -c -
  )

  local extract_dir="${TMP_DIR}/extract_${abi}"
  mkdir -p "${extract_dir}"
  tar -xzf "${TMP_DIR}/${asset}" -C "${extract_dir}"

  cp "${extract_dir}/aether" "${JNI_OUT}/${abi}/libaether.so"
  cp "${extract_dir}/pt/psiphon-tunnel-core" "${JNI_OUT}/${abi}/libpsiphon.so"
  cp "${extract_dir}/pt/lyrebird" "${JNI_OUT}/${abi}/liblyrebird.so"
  chmod 0755 "${JNI_OUT}/${abi}/libaether.so" "${JNI_OUT}/${abi}/libpsiphon.so" "${JNI_OUT}/${abi}/liblyrebird.so"
}

sync_aether_abi "aether-android-arm64.tar.gz" "arm64-v8a"
sync_aether_abi "aether-android-armv7.tar.gz" "armeabi-v7a"
sync_aether_abi "aether-android-x86_64.tar.gz" "x86_64"

echo "==> Downloading hev-socks5-tunnel ${HEV_TAG} AAR..."
curl -fsSL -L "${HEV_AAR_URL}" -o "${LIBS_OUT}/hev-socks5-tunnel.aar"

echo "Done! Synced native binaries to ${JNI_OUT} and ${LIBS_OUT}."
