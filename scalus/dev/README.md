# Developer scripts

Run the browser compiler outside the browser, to reproduce problems quickly and with real stack
traces. They use the compiler module the build produces (`compiler/target/scala3-compiler-sjs/
scala3-compiler-sjs-opt/main.js`, or `-fastopt/` for function names in stack traces).

All need Node 24 with Wasm flags: `node --experimental-wasm-exnref --experimental-wasm-jspi
--experimental-wasm-imported-strings <script> ...`

| Script | Does |
|---|---|
| `node-scalac.mjs <main.js> <scalac args...>` | One compile with a fresh context (`runScala3CompilerSJSAsync`) |
| `node-session.mjs <main.js> <classpath> <outDir> <runner.scala> <sources...>` | The browser IDE flow: a discovery compile, then a final compile with the runner, in one retained session |
| `node-link.mjs <main.js> <runtime IR zips,> <out.js> <program IR dirs...>` | Links programs with the in-browser (Wasm) Scala.js linker, several in one session |
| `cors-serve.py <port> <dir>` | Static server with CORS and no caching, to serve a toolchain from another origin |
