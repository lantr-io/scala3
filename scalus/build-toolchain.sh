#!/usr/bin/env bash
# Build the Scalus browser toolchain: the Scala.js/Wasm compiler with the Scalus plugin linked in,
# plus Scalus jars, runtime IR and @noble bundles, ready to serve as static files.
# Usage: scalus/build-toolchain.sh <scalus checkout at scalus/SCALUS_REF> <output .tar.gz>
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
scalus="$(cd "$1" && pwd)"
out="$2"
ide="$root/compiler/browser-ide"
work="$(mktemp -d)"

echo "== Scalus JS jar and classpath ($(git -C "$scalus" rev-parse --short HEAD))"
(cd "$scalus" && sbt -Dsbt.supershell=false scalusJS/packageBin)
# -batch -no-colors keeps terminal escape codes out of the output; pick the classpath line itself.
cp_line="$(cd "$scalus" && sbt -batch -no-colors -Dsbt.supershell=false --error \
  "export scalusJS/Runtime/fullClasspath" | grep '/scalus-core/js/target/' | tail -1)"
[[ -n "$cp_line" ]] || { echo "Could not read the Scalus JS classpath" >&2; exit 1; }
jar="$(ls "$scalus"/scalus-core/js/target/scala-3*/scalus_sjs1_3-*.jar | head -1)"
classes="$(dirname "$jar")/classes"
echo "${cp_line//$classes/$jar}" > "$work/classpath.txt"

echo "== Compiler + browser IDE assets"
(cd "$root/compiler" && npm ci --no-audit --no-fund)
(cd "$root" && sbt -Dsbt.supershell=false "scala3-compiler-sjs/prepareBrowserIDE")
python3 -I "$root/scalus/assets.py" "$ide" "$work/classpath.txt"
"$root/scalus/noble/build.sh" "$ide/npm-vendor"

echo "== Package"
tar -czf "$out" -C "$root/compiler" \
  browser-ide/index.html browser-ide/app.js browser-ide/worker.js browser-ide/memory-fs.js \
  browser-ide/styles.css browser-ide/assets browser-ide/npm-vendor
ls -la "$out"
