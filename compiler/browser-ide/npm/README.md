# scala3-compiler-sjs-browser

The Scala 3 compiler, compiled to JavaScript/WebAssembly, packaged to run **entirely
in the browser** inside a dedicated Web Worker. Give it Scala source files and it
compiles and runs them client-side — no server round-trip, no JVM.

This package ships:

- `client.js` — the public ESM API (no DOM dependencies). Owns the worker lifecycle
  and the page ↔ worker message protocol.
- `worker.js` — the Web Worker bootstrap that loads the compiler and runtime.
- `memory-fs.js` — the in-worker virtual filesystem (used by `worker.js`).
- `dist/` — the compiler payload: the WebAssembly compiler module, the standard
  library and Scala.js jars, and the intermediate-representation archives. Every path
  under `dist/` except `manifest.json` is content-hashed.

## Browser requirements

The compiler runs on the **WebAssembly JS Promise Integration (JSPI)** API, surfaced
as `WebAssembly.Suspending`. That means:

- **Chromium 137+** (Chrome, Edge, and other Chromium browsers). Some earlier
  versions expose it behind `chrome://flags/#enable-experimental-webassembly-jspi`.
- Firefox and Safari do not ship JSPI as of this writing.

Always gate on `isSupported()` and provide a fallback (for example, a server-side
compile endpoint) for browsers that cannot run it:

```js
import { isSupported } from "scala3-compiler-sjs-browser";

if (!isSupported()) {
  // Fall back to a server-side compiler, or show a "use a recent Chromium" notice.
}
```

The worker is a **module worker** (`new Worker(url, { type: "module" })`), supported
by all browsers new enough to have JSPI.

## Install

```sh
npm install scala3-compiler-sjs-browser
```

## How it loads

`client.js` spawns `worker.js`. The worker reads `dist/manifest.json`, then fetches
the hashed assets it references (the compiler module, jars, and IR archives). Two
things therefore have to be reachable at runtime:

1. **`worker.js`** — loaded relative to `client.js` via
   `new URL("./worker.js", import.meta.url)`, which every modern bundler rewrites
   correctly. You normally do not configure this.
2. **`dist/`** — fetched over HTTP by the worker using the URL you pass as
   `manifestUrl`. These files are **not** imported through your bundler; they must be
   served as static assets, and `manifestUrl` must point at the served
   `dist/manifest.json`.

The single most portable setup, regardless of bundler, is:

> Copy `node_modules/scala3-compiler-sjs-browser/dist` into your app's static
> directory and pass its served location as `manifestUrl`.

**Resolution rule:** every asset URL inside `manifest.json` is resolved **relative to
the manifest itself** — exactly the way a browser resolves URLs found inside a fetched
document. There is no other base and no special-casing. This is why `dist/` works from
any path (site root, a sub-directory, or a separate CDN origin) as long as its internal
layout is preserved, and it is the one rule to follow if you ever hand-author or
rewrite a manifest.

## Quickstart

```js
import { createCompilerClient, isSupported } from "scala3-compiler-sjs-browser";

if (!isSupported()) {
  throw new Error("This browser lacks WebAssembly JSPI; use Chromium 137+.");
}

const client = createCompilerClient({
  // Where you serve the copied dist/ directory:
  manifestUrl: "/scala/dist/manifest.json",
  onStatus: (text) => console.log("[compiler]", text),
});

await client.ready;

const result = await client.run({
  files: [
    {
      path: "Main.scala",
      content: `@main def hello() = println("Hello from the browser!")`,
    },
  ],
});

console.log(result.ok, result.output); // true, "Hello from the browser!\n"
```

### Vite

`client.js` and `worker.js` are ESM and use the standard
`new Worker(new URL("./worker.js", import.meta.url), { type: "module" })` pattern, so
Vite bundles the worker with no extra config.

You only need to make `dist/` available as static files. Two options:

**Option A — copy into `public/` (simplest).** Copy the package's `dist/` into your
Vite `public/` directory (e.g. `public/scala/dist`) as a build/prepare step:

```jsonc
// package.json
{
  "scripts": {
    "prepare-compiler": "cp -R node_modules/scala3-compiler-sjs-browser/dist public/scala/dist"
  }
}
```

Then point at it:

```js
createCompilerClient({ manifestUrl: "/scala/dist/manifest.json" });
```

**Option B — `vite-plugin-static-copy`.** Copy `dist/` verbatim into the build output:

```js
// vite.config.js
import { defineConfig } from "vite";
import { viteStaticCopy } from "vite-plugin-static-copy";

export default defineConfig({
  plugins: [
    viteStaticCopy({
      targets: [
        {
          src: "node_modules/scala3-compiler-sjs-browser/dist/*",
          dest: "scala/dist",
        },
      ],
    }),
  ],
});
```

Do **not** import files under `dist/` through Vite (no `?url`/`?raw` on the compiler
assets) — they must be copied byte-for-byte (see "Asset integrity" below).

### webpack 5

webpack 5 understands `new Worker(new URL("./worker.js", import.meta.url))` natively,
so the worker builds without extra loaders. Copy `dist/` into your output with
`copy-webpack-plugin`:

```js
// webpack.config.js
const path = require("path");
const CopyPlugin = require("copy-webpack-plugin");

module.exports = {
  plugins: [
    new CopyPlugin({
      patterns: [
        {
          from: path.resolve(
            __dirname,
            "node_modules/scala3-compiler-sjs-browser/dist"
          ),
          to: "scala/dist",
        },
      ],
    }),
  ],
};
```

```js
createCompilerClient({ manifestUrl: "/scala/dist/manifest.json" });
```

### No bundler (static hosting)

You can use the package with nothing but static file hosting. Copy `client.js`,
`worker.js`, `memory-fs.js`, and `dist/` from the package to a directory on your
server (keeping them siblings — `worker.js` imports `./memory-fs.js`), then load the
client from a module script:

```
your-site/
  index.html
  scala/
    client.js
    worker.js
    memory-fs.js
    dist/…
```

```html
<!doctype html>
<meta charset="utf-8" />
<title>Scala in the browser</title>
<button id="run">Run</button>
<pre id="out"></pre>

<script type="module">
  import { createCompilerClient, isSupported } from "./scala/client.js";

  const out = document.querySelector("#out");
  if (!isSupported()) {
    out.textContent = "This browser needs WebAssembly JSPI (Chromium 137+).";
  } else {
    const client = createCompilerClient({
      manifestUrl: "./scala/dist/manifest.json",
      onStatus: (text) => (out.textContent = text),
    });
    await client.ready;

    document.querySelector("#run").addEventListener("click", async () => {
      const result = await client.run({
        files: [
          { path: "Main.scala", content: `@main def hi() = println("hello")` },
        ],
      });
      out.textContent = result.output;
    });
  }
</script>
```

Serve with correct MIME types — in particular `.wasm` as `application/wasm`. `client.js`
resolves `./worker.js` relative to its own URL, so keep the three `.js` files together.

## API reference

### `isSupported(): boolean`

Returns `true` when the current environment exposes `WebAssembly.Suspending` (JSPI).
Call this before `createCompilerClient` and fall back when it returns `false`.

### `createCompilerClient(options): CompilerClient`

Spawns the worker and returns a client. The worker starts loading immediately; wait
on `ready` before calling `run`.

**Options**

| Option            | Type                        | Required | Description |
| ----------------- | --------------------------- | -------- | ----------- |
| `manifestUrl`     | `string \| URL`             | yes      | URL of `dist/manifest.json`. Relative URLs resolve against the document base. |
| `workerUrl`       | `string \| URL`             | no       | Worker script URL. Defaults to the bundled `worker.js`. Override only for unusual hosting setups. |
| `onStatus`        | `(text: string) => void`    | no       | Human-readable progress updates while loading and running. |
| `onAwaitingInput` | `(output: string) => void`  | no       | Fired when a running program blocks on standard input. `output` is the program output so far. Continue with `sendStdin`. |
| `onError`         | `(message: string) => void` | no       | Fired on any worker runtime error (also rejects the relevant promise). |

**Returned `CompilerClient`**

#### `ready: Promise<void>`

Resolves once the compiler has loaded and is ready to run. Rejects if the worker
fails to initialize.

#### `run({ files, diagnostics? }): Promise<RunResult>`

Compiles and runs the given sources. Only one run may be in flight at a time; calling
`run` again before the previous run settles throws synchronously.

- `files: { path: string, content: string }[]` — sources to compile. Paths are
  in-worker virtual paths, e.g. `"Main.scala"` or `"pkg/Foo.scala"`.
- `diagnostics?: boolean` — when `true`, include compiler diagnostics in `output`.

Resolves with a `RunResult`:

```ts
{
  ok: boolean;                 // true if compilation AND execution succeeded
  output: string;              // combined program / compiler output
  compileDurationMs: number | null;   // total compile time for this run
  phases: { name: string, durationMs: number }[] | null; // per-phase timings
  compilerLog: string[] | null;       // raw compiler log lines
}
```

A compilation or runtime *failure of the program* resolves with `ok: false` (it does
**not** reject). The promise only **rejects** when the worker itself hits a fatal
runtime error (which also fires `onError` and prevents further use of the client).

#### `sendStdin(line: string): void`

Supplies one line of standard input to a program that is awaiting input. Legal only
after `onAwaitingInput` has fired for the current run; throws otherwise. The original
`run` promise stays pending and later resolves (or awaits input again).

```js
const client = createCompilerClient({
  manifestUrl: "/scala/dist/manifest.json",
  onAwaitingInput: (soFar) => {
    console.log(soFar);        // prompt printed by the program
    client.sendStdin("Alice"); // answer it
  },
});
await client.ready;

const result = await client.run({
  files: [
    {
      path: "Main.scala",
      content: `@main def greet() =
  val name = scala.io.StdIn.readLine()
  println(s"Hello, $name!")`,
    },
  ],
});
// result.output === "Hello, Alice!\n"
```

#### `importMacroArtifacts(files): Promise<MacroImportResult>`

Loads precompiled macro artifacts (jars / `.sjsir` zips) so that later `run` calls can
expand macros they define. Only one import may be in flight at a time.

- `files: { name: string, bytes: Uint8Array }[]`.

The `bytes` buffers are **transferred** to the worker for efficiency — do not reuse
them after calling. Resolves with `{ ok: boolean, output: string }`.

```js
const bytes = new Uint8Array(await (await fetch("/macros/my-macros.jar")).arrayBuffer());
const { ok, output } = await client.importMacroArtifacts([
  { name: "my-macros.jar", bytes },
]);
```

#### `terminate(): void`

Terminates the worker and rejects every pending promise. The client cannot be reused;
create a new one to compile again.

## Caching

Everything under `dist/` is content-addressed: asset filenames (and the directories
containing the compiler module) embed a hash of their bytes, so a given URL always
serves the same content. Serve them immutably and long-lived:

```
# Everything under dist/, except manifest.json
Cache-Control: public, max-age=31536000, immutable
```

`dist/manifest.json` is the one entry point that is not hashed — it points at the
current asset versions. Serve it so clients revalidate and pick up new builds:

```
# dist/manifest.json
Cache-Control: no-cache
```

## Asset integrity

The compiler entry files — `dist/compiler-*/main.js`, `dist/compiler-*/__loader.js`,
and `dist/compiler-*/main.wasm` — depend on their exact names and on staying siblings:
`main.js` imports `./__loader.js`, `__loader.js` loads `./main.wasm`, and the runtime
derives one path from another. **Never let a bundler rename, fingerprint, inline, or
otherwise transform these files.** Because you serve `dist/` as static assets (copying
it verbatim rather than importing it), this is handled for you — just make sure any
copy step preserves the directory structure and does not run the files through an asset
pipeline.

## Package size

The bulk of the download is `dist/`: the WebAssembly compiler module, the Java/Scala
standard-library jars, the Scala.js library, and the IR archives. Expect the `dist/`
payload to be on the order of tens of megabytes uncompressed; enable gzip/brotli on
your server and the immutable caching above so it is fetched once and reused across
sessions. `client.js`, `worker.js`, and `memory-fs.js` themselves are only a few
kilobytes.

## Versioning

The package `version` tracks the Scala compiler version. `package.json` also carries a
`scala3sjs` field with build provenance:

```jsonc
{
  "scala3sjs": {
    "gitSha": "…",          // source commit the bundle was built from
    "assetVersion": "…",    // hash of the compiler module; changes when the compiler changes
    "compilerVersion": "…"  // the Scala 3 / dotty version
  }
}
```

`assetVersion` is the value baked into `dist/manifest.json` (`assetVersion`) and used as
the compiler-directory hash, so you can correlate a running client with the exact bundle
it loaded.

## License

Apache-2.0.
