// Link runtime IR zip + program IR dir with the Wasm-compiled in-browser linker, under Node.
// Usage: node <wasm flags> node-link.mjs <compiler main.js> <runtime zip> <program IR dir> <out.js>
import { pathToFileURL } from "node:url";
import { readFileSync, writeFileSync, readdirSync, statSync } from "node:fs";
import { join } from "node:path";
import { createRequire } from "node:module";
const [compilerMain, runtimeZip, outFile, ...programDirs] = process.argv.slice(2);
const require = createRequire(compilerMain);
const JSZip = require("jszip");
const compiler = await import(pathToFileURL(compilerMain).href);
const paths = [], contents = [];
for (const runtimePart of runtimeZip.split(",")) {  // comma-separated runtime IR parts
  const zip = await JSZip.loadAsync(readFileSync(runtimePart));
  for (const name of Object.keys(zip.files).filter(n => n.endsWith(".sjsir")).sort()) {
    paths.push("/runtime/" + name); contents.push(await zip.files[name].async("uint8array"));
  }
}
const base = { paths: [...paths], contents: [...contents] };
const walk = d => readdirSync(d).flatMap(f => statSync(join(d, f)).isDirectory() ? walk(join(d, f)) : [join(d, f)]);
// Link each program dir in turn with the same linker session, like successive IDE runs.
for (const programDir of programDirs) {
  const ps = [...base.paths], cs = [...base.contents];
  for (const f of walk(programDir).filter(f => f.endsWith(".sjsir")).sort()) {
    ps.push("/workspace/out/" + f.slice(programDir.length)); cs.push(new Uint8Array(readFileSync(f)));
  }
  try {
    const t0 = Date.now();
    const result = await compiler.linkScala3ModuleForNodeAsync(ps, cs);
    writeFileSync(outFile, result.code);
    console.log(`${programDir}: LINK OK in ${Date.now() - t0} ms (${ps.length} IR files)`);
  } catch (e) {
    console.error(`${programDir}: LINK CRASH:`, e && e.stack ? e.stack : e);
  }
}
