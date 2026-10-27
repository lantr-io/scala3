// Emits the Scala.js IR that lets the browser compiler expand Scalus macros without relinking itself:
// macro entry points for every package of the Scalus jars, plus the implementation classes they
// need that the compiler does not already contain. sbt links the output into the compiler
// (compiler/scalus-macros-ir, see Build.scala).
//
// Usage: node <wasm flags> scalus/prelink-macros.mjs <compiler main.js> <browser-ide dir> <output dir>
import { pathToFileURL } from "node:url";
import { mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { createRequire } from "node:module";
import { dirname, join, resolve } from "node:path";

// Scala exceptions reach JS as objects Node cannot print; show what there is.
for (const event of ["unhandledRejection", "uncaughtException"]) process.on(event, (e) => {
  const text = (() => { try { return String(e); } catch { return Object.prototype.toString.call(e); } })();
  console.error(`prelink-macros failed: ${text}`, e?.stack ?? "", Object.getOwnPropertyNames(e ?? {}));
  process.exit(1);
});

const [compilerMain, ideDir, outDir] = process.argv.slice(2);
const JSZip = createRequire(resolve(compilerMain))("jszip");
console.log("loading the compiler…");
const compiler = await import(pathToFileURL(resolve(compilerMain)).href);
const assets = join(ideDir, "assets");
const manifest = JSON.parse(readFileSync(join(assets, "manifest.json"), "utf8"));
const local = (url) => join(assets, url);

async function zipEntries(path, filter) {
  const zip = await JSZip.loadAsync(readFileSync(path));
  const names = Object.keys(zip.files).filter((n) => !zip.files[n].dir && filter(n));
  return Promise.all(names.map(async (n) => [n, await zip.files[n].async("uint8array")]));
}

// Packages to scan: every package with Scala 3 definitions in the Scalus jars.
const scalusJars = manifest.classpath.filter((e) => /\/scalus[-_][^/]*\.jar$/.test(e.path)).map((e) => local(e.url));
const packages = new Set();
for (const jar of scalusJars)
  for (const [name] of await zipEntries(jar, (n) => n.endsWith(".tasty"))) packages.add(dirname(name).replaceAll("/", "."));

// Implementation IR the closure may draw from: the runtime IR, minus classes the compiler has.
const compilerClasses = new Set((await zipEntries(local(manifest.compilerIR), (n) => n.endsWith(".sjsir"))).map(([n]) => n));
const available = [];
for (const part of [].concat(manifest.runtimeIR))
  for (const entry of await zipEntries(local(part), (n) => n.endsWith(".sjsir") && !compilerClasses.has(n))) available.push(entry);

console.log(`${packages.size} packages, ${available.length} implementation candidates; emitting entry points…`);
const scratch = join(outDir, ".scratch"); // compiler output directory, which must exist
mkdirSync(scratch, { recursive: true });
const classpath = manifest.classpath.map((e) => local(e.url)).join(":");
// Macro implementations that need the JVM at compile time (reading files, class reflection) cannot
// run in the browser. Macros that reach one of these are left out and keep failing there as before;
// the other macros of their package are still linked.
const JvmOnly = ["scalus/utils/Macros$.sjsir", "scalus/cardano/onchain/plutus/prelude/EqMacros$.sjsir"];

const { files: ir, skipped } = await compiler.prelinkScala3MacroIRAsync(
  ["-classpath", classpath, "-d", scratch],
  [...packages].sort(),
  available.map(([n]) => n),
  available.map(([, b]) => b),
  JvmOnly,
);
for (const id of skipped) console.log(`left out (needs the JVM): ${id}`);

rmSync(outDir, { recursive: true, force: true });
for (const { path, bytes } of ir) {
  mkdirSync(join(outDir, dirname(path)), { recursive: true });
  writeFileSync(join(outDir, path), bytes);
}
const entryPoints = ir.filter((f) => f.path.includes("sjsmacros/generated/"));
console.log(`scanned ${packages.size} packages: ${entryPoints.length} entry-point classes, ${ir.length - entryPoints.length} implementation classes`);
for (const f of entryPoints) console.log("  " + f.path);
