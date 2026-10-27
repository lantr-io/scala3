"""Copy the Scalus compiler plugin sources into compiler/src-scalus-plugin and patch them for Scala.js.

The plugin is linked statically into the Scala.js/Wasm compiler (see StaticPlugins), so its sources
are compiled with the compiler. build-toolchain.sh runs this on every build, from the Scalus checkout
at scalus/SCALUS_REF; compiler/src-scalus-plugin is generated, not committed.
Usage: python3 -I scalus/sync-plugin.py <scalus checkout>   (run `sbt scalusPlugin/copySharedFiles` there first)
"""
import pathlib
import shutil
import sys

scalus = pathlib.Path(sys.argv[1])
root = pathlib.Path(__file__).resolve().parent.parent
src = scalus / "scalus-plugin" / "src" / "main"
dst = root / "compiler" / "src-scalus-plugin"
shutil.rmtree(dst, ignore_errors=True)
for d in ["scala", "scala-3.8", "shared/scala"]:
    shutil.copytree(src / d, dst / d)

# The compiler is built with -Yexplicit-nulls; the plugin is not written for it.
for p in dst.rglob("*.scala"):
    lines = p.read_text().split("\n")
    i = next(i for i, l in enumerate(lines) if l.startswith("package "))
    lines.insert(i + 1, "import scala.language.unsafeNulls")
    p.write_text("\n".join(lines))


def replace(rel, old, new):
    p = dst / rel
    s = p.read_text()
    if old not in s:
        sys.exit(f"sync-plugin: patch anchor not found in {rel}; update this script")
    p.write_text(s.replace(old, new))


# java.nio.file does not exist on Scala.js. Both spots only matter for JVM builds.
plugin = "scala/scalus/compiler/plugin/Plugin.scala"
s = (dst / plugin).read_text()
start = s.index("            try\n                val path = java.nio.file.Path.of(")
end_marker = "            catch case _: Exception => Set.empty[String]\n"
end = s.index(end_marker) + len(end_marker)
(dst / plugin).write_text(s[:start] + "            Set.empty[String] // Scala.js: no java.nio.file\n" + s[end:])

replace(
    "scala/scalus/compiler/plugin/SIRHelper.scala",
    """        val path = ctx.settings.outputDir.value.file.toPath
            .resolve(s"${ctx.compilationUnit.source.file.name}_${span.start}.sir")
        import java.nio.file.Files
        Files.createDirectories(path.getParent)
        Files.write(path, codedStr.getBytes(StandardCharsets.ISO_8859_1))
        report.echo(s"Scalus: saved SIR to ${path}")""",
    "        // Scala.js: no java.nio.file, skip writing the debug .sir file",
)
print(f"synced {sum(1 for _ in dst.rglob('*.scala'))} files into {dst}")
