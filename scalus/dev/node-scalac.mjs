// Run the Scala.js/Wasm-compiled scalac (with the Scalus plugin) under Node.
// Usage: node <wasm flags> node-scalac.mjs <main.js> <scalac args...>
import { pathToFileURL } from "node:url";
const [compilerMain, ...args] = process.argv.slice(2);
const compiler = await import(pathToFileURL(compilerMain).href);
try {
  const code = await compiler.runScala3CompilerSJSAsync(args);
  console.log(`exit: ${code}`);
} catch (e) {
  console.error("CRASH:", e && e.stack ? e.stack : e);
}
