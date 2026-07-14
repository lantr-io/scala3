import { createMemoryFileSystem } from "./memory-fs.js";

const COMPILER_LOAD_TIMEOUT_MS = 120000;

function formatError(error) {
  return error instanceof Error ? error.message : String(error ?? "");
}

function postStatus(text) {
  self.postMessage({ type: "status", text, output: text });
}

function postRuntimeError(error) {
  self.postMessage({ type: "runtime-error", error: formatError(error) });
}

globalThis.__scala3CompilerSJSStatus = postStatus;

self.addEventListener("error", (event) => {
  postRuntimeError(event.error ?? event.message);
});

self.addEventListener("unhandledrejection", (event) => {
  event.preventDefault();
  postRuntimeError(event.reason);
});

function withTimeout(promise, milliseconds, label) {
  let timeoutId;
  const timeout = new Promise((_, reject) => {
    timeoutId = setTimeout(() => {
      reject(new Error(`${label} timed out after ${Math.round(milliseconds / 1000)} seconds.`));
    }, milliseconds);
  });

  return Promise.race([promise, timeout]).finally(() => clearTimeout(timeoutId));
}

try {
  postStatus("Loading compiler manifest...");
  // The host page can point the worker at any manifest via ?manifest=<url> (used by
  // the published npm bundle). With no query param we fall back to the demo's
  // ./assets/manifest.json. The param only affects where we FIND the manifest; it is
  // always resolved against the worker location.
  const manifestParam = new URL(self.location.href).searchParams.get("manifest");
  const manifestUrl = new URL(manifestParam ?? "./assets/manifest.json", self.location.href);

  const manifestResponse = await fetch(manifestUrl.href, { cache: "no-store" });
  if (!manifestResponse.ok) {
    throw new Error(`Failed to fetch compiler manifest: ${manifestResponse.status} ${manifestResponse.statusText}`);
  }
  const manifest = await manifestResponse.json();

  // Every asset URL in the manifest is resolved relative to the manifest itself, just
  // as a browser resolves URLs found inside a fetched document. That single rule makes
  // the bundle location-independent and lets the Scala side's global fetch() and this
  // module's import() work no matter where the worker is hosted.
  const absolutize = (value) => new URL(value, manifestUrl).href;
  manifest.compilerModule = absolutize(manifest.compilerModule);
  manifest.compilerIR = absolutize(manifest.compilerIR);
  manifest.runtimeIR = absolutize(manifest.runtimeIR);
  if (Array.isArray(manifest.classpath)) {
    for (const entry of manifest.classpath) {
      if (entry && typeof entry.url === "string") {
        entry.url = absolutize(entry.url);
      }
    }
  }
  if (typeof manifest.jszipWrapper === "string") {
    manifest.jszipWrapper = absolutize(manifest.jszipWrapper);
  }

  const fs = createMemoryFileSystem();
  globalThis.__scala3CompilerSJSHostFS = fs.hostFS;

  postStatus("Loading compiler bundle...");
  const compilerModuleURL = new URL(manifest.compilerModule);
  compilerModuleURL.searchParams.set("v", String(manifest.assetVersion ?? Date.now()));
  const compiler = await withTimeout(
    import(compilerModuleURL.href),
    COMPILER_LOAD_TIMEOUT_MS,
    "Loading the Scala.js compiler bundle"
  );
  postStatus("Initializing compiler runtime...");
  compiler.startScala3BrowserIDEWorker({
    manifest,
    fs,
    // Manifests without a jszipWrapper field (older/third-party ones) fall back to the
    // legacy path relative to the worker location.
    jszipWrapperUrl: manifest.jszipWrapper ?? new URL("./assets/vendor/jszip-wrapper.js", self.location.href).href,
  });
} catch (error) {
  postRuntimeError(error);
}
