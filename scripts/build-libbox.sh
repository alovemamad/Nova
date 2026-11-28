#!/usr/bin/env bash
set -euo pipefail

# Optional helper script to compile sing-box libbox.aar from official SagerNet/sing-box source.
# Output is written to app/libs/libbox.aar (gitignored by default so builds stay fast & offline-safe).
#
# Prerequisites:
#   - Go >= 1.22
#   - Android SDK + NDK (ANDROID_HOME / ANDROID_NDK_HOME)
#   - Java 17+

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_AAR="${ROOT_DIR}/app/libs/libbox.aar"
SING_BOX_REF="${SING_BOX_REF:-v1.12.12}"
BUILD_TAGS="${BUILD_TAGS:-with_gvisor,with_clash_api,with_utls,with_ech}"

WORK_DIR="$(mktemp -d)"
trap 'rm -rf "${WORK_DIR}"' EXIT

echo "==> Installing gomobile..."
go install github.com/sagernet/gomobile/cmd/gomobile@latest
go install github.com/sagernet/gomobile/cmd/gobind@latest
export PATH="$(go env GOPATH)/bin:${PATH}"
gomobile init

echo "==> Cloning SagerNet/sing-box (${SING_BOX_REF})..."
git clone --depth 1 --branch "${SING_BOX_REF}" https://github.com/SagerNet/sing-box.git "${WORK_DIR}/sing-box"

mkdir -p "$(dirname "${OUT_AAR}")"
(
  cd "${WORK_DIR}/sing-box"
  echo "==> Building libbox.aar with tags: ${BUILD_TAGS}..."
  if [[ -f "cmd/internal/build_libbox/main.go" ]]; then
    go run ./cmd/internal/build_libbox -target android -tags "${BUILD_TAGS}" -o "${OUT_AAR}"
  else
    gomobile bind -v -androidapi 26 -trimpath -tags "${BUILD_TAGS}" -o "${OUT_AAR}" ./experimental/libbox
  fi
)

echo "Built ${OUT_AAR} successfully."
