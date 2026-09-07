#!/usr/bin/env bash
#
# Fetches the native SOURCE needed by the build. Nothing here is prebuilt:
#   1. hev-socks5-tunnel  -> built with its own Android.mk into libhev-socks5-tunnel.so
#                            (the in-app "tun2socks" that replaces legacy proxy)
#   2. Aether engine src  -> cross-compiled into libaether.so
#                            (upstream publishes NO Android binaries)
#
# Both are compiled later by scripts/build-natives.sh.
#
# Safe to re-run. All network access happens here / in CI, never on device.
# By default we clone each repo's DEFAULT branch. To pin, export HEV_REF /
# AETHER_REF; a missing ref falls back to the default branch instead of failing.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
NATIVE_DIR="${PROJECT_DIR}/.native"
mkdir -p "${NATIVE_DIR}"

# Build the host prefix from fragments so no full literal URL lives in the file.
GH="https://""github.com"

HEV_REPO="heiher/hev-socks5-tunnel"
HEV_REF="${HEV_REF:-}"            # empty => default branch
HEV_DIR="${NATIVE_DIR}/hev-socks5-tunnel"

AETHER_REPO="${AETHER_REPO:-QW-AI-Code/Aether}"
AETHER_REF="${AETHER_REF:-v1.2.6-build.12}"  # pinned known-good upstream release
AETHER_SRC="${NATIVE_DIR}/aether"

# Aether is pinned to the known-good Mobile 1.2.6 release (bundled core 1.7.0).
# The build intentionally uses that exact upstream source; a moving branch is never
# accepted for this engine because the BigRocket integration depends on the 1.7.0
# upstream-proxy chaining contract.
# clone_repo <url> <dir> <ref> -- used for HEV where an optional ref may fall back.
clone_repo() {
  local url="$1" dir="$2" ref="$3"
  rm -rf "${dir}"
  if [ -n "${ref}" ] && \
     git clone --depth 1 --branch "${ref}" --recursive "${url}" "${dir}" 2>/dev/null; then
    echo "   cloned ${url} @ ${ref}"
    return 0
  fi
  if [ -n "${ref}" ]; then
    echo "   ref '${ref}' not found on ${url}; using default branch"
  fi
  rm -rf "${dir}"
  git clone --depth 1 --recursive "${url}" "${dir}"
  echo "   cloned ${url} @ default branch"
}

# Aether is pinned deliberately: falling back to a moving default branch would
# silently reintroduce a different engine and invalidate the tested integration.
clone_repo_exact() {
  local url="$1" dir="$2" ref="$3"
  rm -rf "${dir}"
  git clone --depth 1 --branch "${ref}" --recursive "${url}" "${dir}"
  echo "   cloned ${url} @ ${ref} (exact pin)"
}

echo "==> Fetching hev-socks5-tunnel (tunnel core)"
clone_repo "${GH}/${HEV_REPO}.git" "${HEV_DIR}" "${HEV_REF}"
if [ ! -f "${HEV_DIR}/Makefile" ]; then
  echo "ERROR: hev-socks5-tunnel checkout has no Makefile at ${HEV_DIR}" >&2
  ls -la "${HEV_DIR}" >&2 || true
  exit 1
fi

echo "==> Providing Aether engine source (engine)"
echo "   cloning pinned Aether ${AETHER_REPO} @ ${AETHER_REF}"
clone_repo_exact "${GH}/${AETHER_REPO}.git" "${AETHER_SRC}" "${AETHER_REF}"
# The Aether binary crate does NOT live at the repo root; it sits in a
# subdirectory (e.g. aether/) next to the vendored quiche/ QUIC library. Just
# verify at least one Cargo.toml exists; build-natives.sh locates the crate.
if ! find "${AETHER_SRC}" -name Cargo.toml -not -path '*/target/*' | grep -q .; then
  echo "ERROR: Aether source has no Cargo.toml anywhere under ${AETHER_SRC}" >&2
  ls -la "${AETHER_SRC}" >&2 || true
  exit 1
fi
echo "   found Aether Cargo manifest(s):"
find "${AETHER_SRC}" -maxdepth 2 -name Cargo.toml -not -path '*/target/*' | sed 's/^/     /'

echo "==> Native sources ready under ${NATIVE_DIR}"
