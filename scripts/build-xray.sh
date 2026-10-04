#!/usr/bin/env bash
#
# Cross-compiles the Xray core (XTLS/Xray-core) into app/src/main/jniLibs/<abi>/libxray.so.
#
# Same packaging mechanism as libaether.so: a native EXECUTABLE shipped beside the JNI
# libraries. useLegacyPackaging=true guarantees it is extracted to
# ApplicationInfo.nativeLibraryDir with the exec bit set, which XrayProcess then spawns
# as a child process (`libxray.so run -c config.json`).
#
# Built with GOOS=android + the NDK clang per ABI so the result is a proper Android PIE
# executable (bionic-linked); a plain GOOS=linux build is NOT reliable on Android 10+.
#
# Requires: ANDROID_NDK_HOME, Go >= 1.24 on PATH.
# To pin a release, export XRAY_REF (tag); default is the repo's default branch.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
NATIVE_DIR="${PROJECT_DIR}/.native"
XRAY_SRC="${NATIVE_DIR}/xray-core"
JNI_DIR="${PROJECT_DIR}/app/src/main/jniLibs"

API="${ANDROID_API:-26}"
XRAY_REPO="${XRAY_REPO:-XTLS/Xray-core}"
XRAY_REF="${XRAY_REF:-}"

GH="https://""github.com"

if [ -z "${ANDROID_NDK_HOME:-}" ] || [ ! -d "${ANDROID_NDK_HOME}" ]; then
  echo "ERROR: ANDROID_NDK_HOME is not set or does not exist." >&2
  exit 1
fi
if ! command -v go >/dev/null; then
  echo "ERROR: go toolchain not found on PATH." >&2
  exit 1
fi

NDK_TOOLCHAIN=""
for host in linux-x86_64 darwin-x86_64 windows-x86_64; do
  if [ -d "${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/${host}/bin" ]; then
    NDK_TOOLCHAIN="${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/${host}/bin"
    break
  fi
done
if [ -z "${NDK_TOOLCHAIN}" ]; then
  echo "ERROR: could not find the NDK LLVM toolchain under ${ANDROID_NDK_HOME}" >&2
  exit 1
fi

mkdir -p "${NATIVE_DIR}"
if [ ! -f "${XRAY_SRC}/go.mod" ]; then
  rm -rf "${XRAY_SRC}"
  if [ -n "${XRAY_REF}" ] && \
     git clone --depth 1 --branch "${XRAY_REF}" "${GH}/${XRAY_REPO}.git" "${XRAY_SRC}" 2>/dev/null; then
    echo "==> cloned ${XRAY_REPO} @ ${XRAY_REF}"
  else
    rm -rf "${XRAY_SRC}"
    git clone --depth 1 "${GH}/${XRAY_REPO}.git" "${XRAY_SRC}"
    echo "==> cloned ${XRAY_REPO} @ default branch"
  fi
fi

build_abi() {
  local abi="$1" goarch="$2" cc="$3" extra_env=("${@:4}")
  local out="${JNI_DIR}/${abi}/libxray.so"
  mkdir -p "${JNI_DIR}/${abi}"
  echo "==> [xray] building for ${abi} (GOARCH=${goarch})"
  ( cd "${XRAY_SRC}" && \
    env CGO_ENABLED=1 GOOS=android "GOARCH=${goarch}" "CC=${NDK_TOOLCHAIN}/${cc}" "${extra_env[@]}" \
      go build -trimpath -buildvcs=false \
        -ldflags="-s -w -buildid= -checklinkname=0" \
        -o "${out}" ./main )
  if [ ! -s "${out}" ]; then
    echo "ERROR: [${abi}] go build produced no output at ${out}" >&2
    exit 1
  fi
  echo "    [${abi}] $(du -h "${out}" | cut -f1) -> ${out}"
}

build_abi "arm64-v8a"   "arm64" "aarch64-linux-android${API}-clang" "GOFLAGS="
build_abi "armeabi-v7a" "arm"   "armv7a-linux-androideabi${API}-clang" "GOARM=7"

echo "==> Xray cores installed under ${JNI_DIR}"
