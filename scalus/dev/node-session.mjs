// Drive the retained browser compiler session like the IDE worker: discovery compile, then final.
// Usage: node <wasm flags> node-session.mjs <main.js> <classpath> <outDir> <runner.scala> <sources...>
import { pathToFileURL } from "node:url";
const [compilerMain, classpath, outDir, runner, ...sources] = process.argv.slice(2);
const compiler = await import(pathToFileURL(compilerMain).href);
const setup = ["-classpath", classpath, "-d", outDir];
for (const [label, files] of [["discovery", sources], ["final", [...sources, runner]]]) {
  try {
    const code = await compiler.runScala3BrowserSessionAsync(setup, files);
    console.log(`${label}: exit ${code}`);
  } catch (e) {
    console.error(`${label}: CRASH`, e && e.stack ? e.stack : e);
  }
}
