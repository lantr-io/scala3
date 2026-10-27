#!/usr/bin/env bash
# Build the Scalus browser toolchain: the Scala.js/Wasm compiler with the Scalus plugin linked in,
# plus Scalus jars, runtime IR and @noble bundles, ready to serve as static files.
# Usage: scalus/build-toolchain.sh <scalus checkout at scalus/SCALUS_REF (a tag, e.g. v1.4.1)> <output .tar.gz>
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
scalus="$(cd "$1" && pwd)"
out="$2"
ide="$root/compiler/browser-ide"
work="$(mktemp -d)"

echo "== Scalus JS jars and classpath ($(git -C "$scalus" rev-parse --short HEAD))"
# scalus-cardano-ledger brings TxBuilder; its classpath includes scalus-core.
(cd "$scalus" && sbt -Dsbt.supershell=false scalusJS/packageBin scalusCardanoLedgerJS/packageBin)
# -batch -no-colors keeps terminal escape codes out of the output; pick the classpath line itself.
cp_line="$(cd "$scalus" && sbt -batch -no-colors -Dsbt.supershell=false --error \
  "export scalusCardanoLedgerJS/Runtime/fullClasspath" | grep '/scalus-core/js/target/' | tail -1)"
[[ -n "$cp_line" ]] || { echo "Could not read the Scalus JS classpath" >&2; exit 1; }
# Swap each module's classes directory for its packaged jar.
for module in scalus-core scalus-cardano-ledger; do
  jar="$(ls "$scalus"/$module/js/target/scala-3*/*_sjs1_3-*.jar | grep -v -- '-sources\|-javadoc' | head -1)"
  cp_line="${cp_line//$(dirname "$jar")\/classes/$jar}"
done
echo "$cp_line" > "$work/classpath.txt"

echo "== Compiler + browser IDE assets"
(cd "$root/compiler" && npm ci --no-audit --no-fund)
(cd "$root" && sbt -Dsbt.supershell=false "scala3-compiler-sjs/prepareBrowserIDE")
python3 -I "$root/scalus/assets.py" "$ide" "$work/classpath.txt"
"$root/scalus/noble/build.sh" "$ide/npm-vendor"

echo "== Package"
# Toolchain only: the playground has its own UI, so the demo index.html/app.js stay out.
tar -czf "$out" -C "$root/compiler" \
  browser-ide/worker.js browser-ide/memory-fs.js browser-ide/assets browser-ide/npm-vendor
ls -la "$out"
