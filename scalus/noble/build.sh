#!/usr/bin/env bash
# Write the npm modules linked Scalus programs import, as self-contained ESM files under <out>:
# bundles of the @noble modules, and stubs for modules only Scalus's npm facade uses.
# The in-browser runner rewrites `from "pkg/x"` to <out>/pkg/x.js (workers have no import maps).
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
out="$1"
cd "$here"
npm install --no-audit --no-fund >/dev/null
mkdir -p entries
for m in @noble/curves/bls12-381 @noble/curves/ed25519 @noble/curves/secp256k1 \
         @noble/hashes/sha2 @noble/hashes/sha3 @noble/hashes/blake2b @noble/hashes/sha512 \
         @noble/hashes/ripemd160 @noble/hashes/utils; do
  entry="entries/$(echo "$m" | tr '/@' '__').js"
  echo "export * from '$m';" > "$entry"
  npx esbuild "$entry" --bundle --format=esm --log-level=warning --outfile="$out/$m.js"
done
cp -R "$here/stubs/." "$out/"
