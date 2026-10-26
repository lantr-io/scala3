"""Add Scalus jars to the browser IDE assets (classpath + runtime IR). Idempotent.

Run after every `prepareBrowserIDE`, since that task regenerates assets/ and manifest.json.
Usage: python3 -I scalus-assets.py <browser-ide dir> <classpath file>
"""
import json
import pathlib
import shutil
import sys
import zipfile

ide = pathlib.Path(sys.argv[1])
classpath = pathlib.Path(sys.argv[2]).read_text().strip().split(":")

# The IDE runtime already has the Scala 3.8 stdlib and Scala.js library; duplicates break linking.
EXCLUDED = ("scala3-library_", "scala-library-", "scalajs-library_", "scalajs-javalib-", "scalajs-scalalib_")

assets = ide / "assets"
scalus_dir = assets / "scalus"
shutil.rmtree(scalus_dir, ignore_errors=True)
scalus_dir.mkdir()

jars = []
for entry in classpath:
    path = pathlib.Path(entry)
    if path.name.startswith(EXCLUDED):
        continue
    if path.is_dir():
        continue  # scalus-core classes dir; the packaged jar is passed in explicitly
    shutil.copy(path, scalus_dir / path.name)
    jars.append(path.name)

manifest_path = assets / "manifest.json"
manifest = json.loads(manifest_path.read_text())
manifest["classpath"] = [e for e in manifest["classpath"] if not e["path"].startswith("/lib/scalus/")]
# Older manifests hold page-relative URLs (./assets/...); newer ones are relative to the manifest.
prefix = "./assets/" if manifest["compilerModule"].startswith("./assets/") else "./"
manifest["classpath"] += [{"path": f"/lib/scalus/{j}", "url": f"{prefix}scalus/{j}"} for j in jars]

# Runtime IR = the IDE's own runtime IR + every .sjsir from the Scalus jars.
combined = scalus_dir / "scalus-runtime-sjsir.zip"
seen = set()
count = 0
with zipfile.ZipFile(combined, "w", zipfile.ZIP_DEFLATED) as out:
    with zipfile.ZipFile(assets / "runtime" / "runtime-sjsir.zip") as base:  # the IDE's own runtime IR
        for name in base.namelist():
            if name.endswith(".sjsir") and name not in seen:
                seen.add(name)
                out.writestr(name, base.read(name))
    for j in jars:
        with zipfile.ZipFile(scalus_dir / j) as jar:
            for name in jar.namelist():
                if name.endswith(".sjsir") and name not in seen:
                    seen.add(name)
                    out.writestr(name, jar.read(name))
                    count += 1
manifest["runtimeIR"] = f"{prefix}scalus/scalus-runtime-sjsir.zip"
manifest_path.write_text(json.dumps(manifest, indent=2))
print(f"jars: {len(jars)}, scalus IR files: {count}, total IR files: {len(seen)}")
for j in jars:
    print(" ", j)
