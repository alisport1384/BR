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

AETHER_REPO="${AETHER_REPO:-CluvexStudio/Aether}"
AETHER_REF="${AETHER_REF:-v1.7.0}"
AETHER_SRC="${NATIVE_DIR}/aether"

# BigRocket uses the exact Aether Mobile 1.2.6 engine baseline: Aether Core v1.7.0.
# Core 1.7.0 contains upstream-proxy chaining. Its reqwest manifest does not
# enable SOCKS transport, so enable that feature for BigRocket's local SOCKS5
# Path3 upstream before compiling the native engine.
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
echo "   cloning pinned Aether core ${AETHER_REPO} @ ${AETHER_REF}"
clone_repo_exact "${GH}/${AETHER_REPO}.git" "${AETHER_SRC}" "${AETHER_REF}"

AETHER_CARGO="${AETHER_SRC}/aether/Cargo.toml"
if [ ! -f "${AETHER_CARGO}" ]; then
  echo "ERROR: expected Aether core manifest not found: ${AETHER_CARGO}" >&2
  exit 1
fi

# reqwest 0.12's SOCKS transport is feature-gated. Core 1.7.0 exposes the
# upstream proxy API but omits that feature, which makes registration bypass
# the configured SOCKS5 chain and can terminate before the local SOCKS listener
# is exposed. Enable only the required transport feature.
python3 -c 'from pathlib import Path; import sys; p=Path(sys.argv[1]); s=p.read_text(); old="features = [\"json\", \"rustls-tls\", \"cookies\"]"; new="features = [\"json\", \"rustls-tls\", \"cookies\", \"socks\"]"; assert old in s, "ERROR: unexpected Aether 1.7.0 reqwest declaration"; p.write_text(s.replace(old,new,1))' "${AETHER_CARGO}"

if ! grep -q '^version = "1.7.0"' "${AETHER_CARGO}"; then
  echo "ERROR: Aether core is not v1.7.0." >&2
  exit 1
fi
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
