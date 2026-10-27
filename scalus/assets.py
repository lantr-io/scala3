"""Add Scalus jars to the browser IDE assets: compile classpath + runtime IR. Idempotent.

Run after every `prepareBrowserIDE`, since that task regenerates assets/ and manifest.json.
Usage: python3 -I scalus/assets.py <browser-ide dir> <classpath file>
"""
import json
import pathlib
import shutil
import sys
import zipfile

ide = pathlib.Path(sys.argv[1])
classpath = pathlib.Path(sys.argv[2]).read_text().strip().split(":")

# The IDE runtime already has the Scala 3.8 stdlib and Scala.js library; duplicates break linking.
STDLIB = ("scala3-library_", "scala-library-", "scalajs-library_", "scalajs-javalib-", "scalajs-scalalib_")


def has_ir(jar: pathlib.Path) -> bool:
    with zipfile.ZipFile(jar) as z:
        return any(n.endswith(".sjsir") for n in z.namelist())


assets = ide / "assets"
scalus_dir = assets / "scalus"
shutil.rmtree(scalus_dir, ignore_errors=True)
scalus_dir.mkdir()

jars = []
for entry in classpath:
    path = pathlib.Path(entry)
    if path.name.startswith(STDLIB) or path.is_dir():
        continue  # classes dirs are passed in as their packaged jars
    if not has_ir(path):
        continue  # a JVM jar on the JS classpath: nothing the browser can use
    shutil.copy(path, scalus_dir / path.name)
    jars.append(path.name)

manifest_path = assets / "manifest.json"
manifest = json.loads(manifest_path.read_text())
# Older manifests hold page-relative URLs (./assets/...); newer ones are relative to the manifest.
prefix = "./assets/" if manifest["compilerModule"].startswith("./assets/") else "./"
manifest["classpath"] = [e for e in manifest["classpath"] if not e["path"].startswith("/lib/scalus/")]
manifest["classpath"] += [{"path": f"/lib/scalus/{j}", "url": f"{prefix}scalus/{j}"} for j in jars]

# Runtime IR = the IDE's own runtime IR + .sjsir from the Scalus jars.
# Written as parts of at most PART_BYTES, so each file stays under per-file host limits
# (Cloudflare Pages: 25 MiB); the worker loads all parts in parallel.
PART_BYTES = 20 * 2**20


def runtime_entries():
    with zipfile.ZipFile(assets / "runtime" / "runtime-sjsir.zip") as base:
        for name in base.namelist():
            if name.endswith(".sjsir"):
                yield name, base.read(name)
    for j in jars:
        with zipfile.ZipFile(scalus_dir / j) as jar:
            for name in jar.namelist():
                if name.endswith(".sjsir"):
                    yield name, jar.read(name)


seen = set()
parts = []
out = None
for name, data in runtime_entries():
    if name in seen:
        continue
    if out is None or out.fp.tell() > PART_BYTES:
        if out is not None:
            out.close()
        parts.append(scalus_dir / f"scalus-runtime-sjsir-{len(parts) + 1}.zip")
        out = zipfile.ZipFile(parts[-1], "w", zipfile.ZIP_DEFLATED, compresslevel=9)
    seen.add(name)
    out.writestr(name, data)
out.close()
manifest["runtimeIR"] = [f"{prefix}scalus/{p.name}" for p in parts]

# The compiler reads signatures from .tasty when a class has one, and linking uses the runtime IR
# above. So classpath jars shrink to what compiling needs (76 -> 35 MiB for Scalus 1.4.1):
# drop .class files of classes that have .tasty (Java classes keep theirs), and drop .sjsir, except
# in the Scalus jar, which the playground also registers as the macro implementation artifact.
MACRO_ARTIFACT = "scalus_sjs1_3-"


def strip_jar(jar: pathlib.Path, keep_ir: bool) -> None:
    with zipfile.ZipFile(jar) as z:
        names = z.namelist()
        tasty_classes = {n[: -len(".tasty")] for n in names if n.endswith(".tasty")}

        def keep(name: str) -> bool:
            if name.endswith(".sjsir"):
                return keep_ir
            if name.endswith(".class"):
                directory, _, file = name.rpartition("/")
                top_level = (directory + "/" if directory else "") + file[: -len(".class")].split("$")[0]
                return top_level not in tasty_classes
            return True

        entries = [(i, z.read(i)) for i in z.infolist() if keep(i.filename)]
    with zipfile.ZipFile(jar, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as out:
        for info, data in entries:
            out.writestr(info.filename, data)


before = sum(p.stat().st_size for p in [*scalus_dir.glob("*.jar"), assets / "classpath" / "scala-lib.jar"])
for j in jars:
    strip_jar(scalus_dir / j, keep_ir=j.startswith(MACRO_ARTIFACT))
strip_jar(assets / "classpath" / "scala-lib.jar", keep_ir=False)
after = sum(p.stat().st_size for p in [*scalus_dir.glob("*.jar"), assets / "classpath" / "scala-lib.jar"])
print(f"classpath jars stripped: {before / 2**20:.1f} -> {after / 2**20:.1f} MiB")

# File sizes by manifest URL, so a page can show download progress against a known total.
manifest_dir = manifest_path.parent
def size_of(url: str) -> int:
    return (manifest_dir / url).stat().st_size
compiler_wasm = str(pathlib.PurePosixPath(manifest["compilerModule"]).parent / "main.wasm")
sized = [e["url"] for e in manifest["classpath"]] + manifest["runtimeIR"] + [manifest["compilerIR"], compiler_wasm]
manifest["sizes"] = {u: size_of(u) for u in sized}
manifest_path.write_text(json.dumps(manifest, indent=2))
print(f"download total: {sum(manifest['sizes'].values()) / 2**20:.1f} MiB")
# @noble bundles (scalus/noble/build.sh); the worker rewrites bare imports to this location.
manifest["npmVendor"] = "./npm-vendor/" if prefix == "./assets/" else "../npm-vendor/"
manifest_path.write_text(json.dumps(manifest, indent=2))

sizes = ", ".join(f"{p.stat().st_size / 2**20:.1f}" for p in parts)
print(f"jars: {len(jars)}, runtime IR files: {len(seen)}, runtime parts (MiB): {sizes}")
for j in jars:
    print(" ", j)
