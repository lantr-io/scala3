// Public API for driving the Scala 3 (Scala.js/WebAssembly) compiler in a browser
// Web Worker. This module has no DOM or UI dependencies: it owns the worker
// lifecycle and the page<->worker message protocol, and exposes promise-based
// operations. See README.md for usage and worker.js for the worker bootstrap.

/**
 * Report whether the current environment can run the compiler.
 *
 * The compiler relies on the WebAssembly JS Promise Integration (JSPI) API,
 * exposed as `WebAssembly.Suspending`. It is available in Chromium 137+ and
 * behind a flag in some earlier versions. Gate on this before creating a client
 * and fall back to a server-side compiler when it returns `false`.
 *
 * @returns {boolean} `true` when the browser exposes `WebAssembly.Suspending`.
 */
export function isSupported() {
  return typeof WebAssembly !== "undefined" && typeof WebAssembly.Suspending === "function";
}

/**
 * @typedef {Object} SourceFile
 * @property {string} path    In-worker virtual path, e.g. `"Main.scala"` or `"pkg/Foo.scala"`.
 * @property {string} content Source text.
 */

/**
 * @typedef {Object} PhaseTiming
 * @property {string} name       Compiler phase name.
 * @property {number} durationMs Wall-clock time spent in the phase.
 */

/**
 * A single compiler diagnostic (error or warning). Positions are **0-based**:
 * `line`/`column` index from 0; `endLine` is the 0-based line containing the span
 * end, while `endColumn`/`endOffset` point just past the last character (exclusive).
 * Every field is always present; unavailable ones are `null`.
 *
 * @typedef {Object} Diagnostic
 * @property {"error"|"warning"} severity Diagnostic severity. (Info-level output is
 *                                        omitted here; it appears in `compilerLog` text.)
 * @property {string} message             Short message text (ANSI-stripped).
 * @property {string} rendered            Full console-style rendering with the source
 *                                        snippet and caret (ANSI-stripped).
 * @property {string|null} file           Source path as you supplied it (e.g. `"Main.scala"`),
 *                                        or `null` when the diagnostic has no user-facing file.
 * @property {number|null} line           0-based line of the diagnostic anchor (the
 *                                        position scalac prints in its header).
 * @property {number|null} column         0-based column of the diagnostic anchor.
 * @property {number|null} endLine        0-based line containing the span end.
 * @property {number|null} endColumn      0-based column just past the span end (exclusive).
 * @property {number|null} offset         0-based start character offset in the file.
 * @property {number|null} endOffset      0-based end character offset (exclusive).
 * @property {string|null} code           Diagnostic id as a string, when known.
 * @property {string|null} name           Diagnostic name/kind, when known.
 */

/**
 * @typedef {Object} RunResult
 * @property {boolean} ok                          `true` if compilation and execution succeeded.
 * @property {string} output                       Combined program/compiler output shown to the user.
 * @property {Diagnostic[]|null} diagnostics       Structured compiler diagnostics for this run's
 *                                                 compile (may be empty), or `null` if the worker
 *                                                 predates the field.
 * @property {number|null} compileDurationMs       Total compile time for this run, or `null` if not reported.
 * @property {PhaseTiming[]|null} phases           Per-phase timings, or `null` if not reported.
 * @property {string[]|null} compilerLog           Raw compiler log lines, or `null` if not reported.
 */

/**
 * @typedef {Object} CompileResult
 * @property {boolean} ok                    `true` if compilation succeeded (no errors).
 * @property {number} exitCode               Compiler exit code (`0` on success, `1` on errors).
 * @property {Diagnostic[]} diagnostics      Structured compiler diagnostics (may be empty).
 * @property {string[]} compilerLog          Raw compiler log lines.
 * @property {number|null} durationMs        Total compile time, or `null` if not reported.
 * @property {PhaseTiming[]|null} phases     Per-phase timings, or `null` if not reported.
 */

/**
 * @typedef {Object} MacroImportResult
 * @property {boolean} ok    `true` if the artifacts were imported successfully.
 * @property {string} output Human-readable summary or error message.
 */

/**
 * @typedef {Object} MacroArtifact
 * @property {string} name        File name, e.g. `"my-macros.jar"`.
 * @property {Uint8Array} bytes   Raw artifact bytes. The underlying `ArrayBuffer` is
 *                                transferred to the worker, so do not reuse it afterwards.
 */

/**
 * @typedef {Object} CompilerClientOptions
 * @property {string|URL} manifestUrl          Required. URL of the bundle's `manifest.json`.
 *                                             Relative URLs resolve against the document base.
 * @property {string|URL} [workerUrl]          Worker script URL. Defaults to the `worker.js`
 *                                             shipped next to this module.
 * @property {(text: string) => void} [onStatus]          Called with human-readable status text.
 * @property {(output: string) => void} [onAwaitingInput] Called when a running program blocks on
 *                                             stdin; `output` is the program output so far. Continue
 *                                             with `sendStdin`.
 * @property {(message: string) => void} [onError]        Called on any worker runtime error.
 */

/**
 * @typedef {Object} CompilerClient
 * @property {Promise<void>} ready
 * @property {(request: { files: SourceFile[], scalacOptions?: string[], diagnostics?: boolean }) => Promise<RunResult>} run
 * @property {(request: { files: SourceFile[], scalacOptions?: string[], diagnostics?: boolean }) => Promise<CompileResult>} compile
 * @property {(line: string) => void} sendStdin
 * @property {(files: MacroArtifact[]) => Promise<MacroImportResult>} importMacroArtifacts
 * @property {() => void} terminate
 */

/**
 * Create a compiler client backed by a dedicated Web Worker.
 *
 * The worker is spawned immediately; wait on the returned `ready` promise before
 * calling `run` or `compile`. At most one `run`-or-`compile` may be in flight at a
 * time (they share a slot: the worker protocol has no request correlation), plus at
 * most one `importMacroArtifacts` alongside it.
 *
 * @param {CompilerClientOptions} options
 * @returns {CompilerClient}
 */
export function createCompilerClient(options = {}) {
  const { manifestUrl, workerUrl, onStatus, onAwaitingInput, onError } = options;
  if (manifestUrl === undefined || manifestUrl === null || manifestUrl === "") {
    throw new TypeError("createCompilerClient requires a manifestUrl option.");
  }

  // Resolve the manifest against the document base (or the current global's
  // location when running inside a worker), so callers can pass a path relative
  // to the hosting page.
  const documentBase = globalThis.document?.baseURI ?? self.location.href;
  const resolvedManifestUrl = new URL(manifestUrl, documentBase);

  // The worker discovers the manifest through the `manifest` query parameter. A
  // caller-supplied workerUrl resolves against the document base (like manifestUrl),
  // so a relative string works; the default resolves against this module's URL so it
  // points at the worker.js shipped beside client.js. Constructing a fresh URL also
  // preserves any query params the worker URL already carries.
  const resolvedWorkerUrl = workerUrl !== undefined && workerUrl !== null
    ? new URL(workerUrl, documentBase)
    : new URL("./worker.js", import.meta.url);
  resolvedWorkerUrl.searchParams.set("manifest", resolvedManifestUrl.href);

  const worker = new Worker(resolvedWorkerUrl, { type: "module" });

  // --- Client state -------------------------------------------------------
  let terminated = false;
  let readyResolved = false;
  let readyResolve;
  let readyReject;
  const ready = new Promise((resolve, reject) => {
    readyResolve = resolve;
    readyReject = reject;
  });

  // At most one run-or-compile and one macro import can be pending; the protocol has
  // no request IDs, so results are matched by "the currently pending operation". run
  // and compile share a slot (a concurrent run + compile would misroute the
  // compile-duration/awaiting-input messages, which are unlabelled and run-only).
  let pendingRun = null; // { resolve, reject }
  let pendingCompile = null; // { resolve, reject }
  let awaitingInput = false; // program is blocked on stdin within pendingRun
  let lastCompileDuration = null; // { compileDurationMs, phases, compilerLog } for the active run
  let pendingImport = null; // { resolve, reject }

  function callSafely(callback, argument) {
    if (typeof callback !== "function") {
      return;
    }
    try {
      callback(argument);
    } catch (_) {
      // Never let a consumer callback break the message loop.
    }
  }

  // Reject every outstanding promise. Called on fatal errors and terminate().
  function rejectAllPending(error) {
    if (!readyResolved) {
      readyResolved = true;
      readyReject(error);
    }
    if (pendingRun) {
      const pending = pendingRun;
      pendingRun = null;
      awaitingInput = false;
      pending.reject(error);
    }
    if (pendingCompile) {
      const pending = pendingCompile;
      pendingCompile = null;
      pending.reject(error);
    }
    if (pendingImport) {
      const pending = pendingImport;
      pendingImport = null;
      pending.reject(error);
    }
  }

  function handleRuntimeError(rawMessage) {
    const message = rawMessage == null || rawMessage === "" ? "The compiler worker reported a runtime error." : String(rawMessage);
    const error = new Error(message);
    // Rejects ready (if still pending) and any in-flight run/compile/import.
    rejectAllPending(error);
    callSafely(onError, message);
  }

  worker.addEventListener("message", (event) => {
    const message = event.data;
    if (!message || typeof message.type !== "string") {
      return;
    }

    switch (message.type) {
      case "status":
        callSafely(onStatus, typeof message.text === "string" ? message.text : String(message.output ?? ""));
        break;

      case "ready":
        if (!readyResolved) {
          readyResolved = true;
          readyResolve();
        }
        break;

      case "compile-duration":
        // Always arrives before the matching run-result. Capture the latest so
        // run() can attach it to the resolved result.
        lastCompileDuration = {
          compileDurationMs: typeof message.durationMs === "number" ? message.durationMs : null,
          phases: Array.isArray(message.phases) ? message.phases : null,
          compilerLog: Array.isArray(message.compilerLog) ? message.compilerLog : null,
        };
        break;

      case "awaiting-input":
        // The run stays pending; hand the partial output to the consumer, which
        // is expected to continue the run via sendStdin.
        awaitingInput = true;
        callSafely(onAwaitingInput, String(message.output ?? ""));
        break;

      case "run-result": {
        if (!pendingRun) {
          break;
        }
        const pending = pendingRun;
        pendingRun = null;
        awaitingInput = false;
        const timing = lastCompileDuration ?? { compileDurationMs: null, phases: null, compilerLog: null };
        lastCompileDuration = null;
        pending.resolve({
          ok: Boolean(message.ok),
          output: String(message.output ?? ""),
          // Read straight from run-result; null signals a worker that predates the field.
          diagnostics: Array.isArray(message.diagnostics) ? message.diagnostics : null,
          compileDurationMs: timing.compileDurationMs,
          phases: timing.phases,
          compilerLog: timing.compilerLog,
        });
        break;
      }

      case "compile-result": {
        if (!pendingCompile) {
          break;
        }
        const pending = pendingCompile;
        pendingCompile = null;
        // A non-empty `error` marks an invalid request or pre-compile failure
        // (e.g. duplicate paths); it rejects. Compilation errors resolve with ok:false.
        if (typeof message.error === "string" && message.error !== "") {
          pending.reject(new Error(message.error));
          break;
        }
        pending.resolve({
          ok: Boolean(message.ok),
          exitCode: typeof message.exitCode === "number" ? message.exitCode : (message.ok ? 0 : 1),
          diagnostics: Array.isArray(message.diagnostics) ? message.diagnostics : [],
          compilerLog: Array.isArray(message.compilerLog) ? message.compilerLog : [],
          durationMs: typeof message.durationMs === "number" ? message.durationMs : null,
          phases: Array.isArray(message.phases) ? message.phases : null,
        });
        break;
      }

      case "macro-import-result": {
        if (!pendingImport) {
          break;
        }
        const pending = pendingImport;
        pendingImport = null;
        pending.resolve({
          ok: Boolean(message.ok),
          output: String(message.output ?? ""),
        });
        break;
      }

      case "runtime-error":
        handleRuntimeError(message.error);
        break;

      default:
        break;
    }
  });

  worker.addEventListener("error", (event) => {
    event.preventDefault?.();
    handleRuntimeError(event.message || "The compiler worker failed before it could report an error.");
  });

  worker.addEventListener("messageerror", () => {
    handleRuntimeError("The compiler worker sent a message that could not be deserialized.");
  });

  // Enforce the shared run-or-compile slot. Called synchronously before starting
  // either operation, so a violation throws back to the caller. `operation` names
  // the one being attempted; the message names both because either can hold the slot.
  function ensureIdleForCompileOrRun(operation) {
    if (terminated) {
      throw new Error("The compiler client has been terminated.");
    }
    if (pendingRun || pendingCompile) {
      throw new Error(
        `Cannot start a ${operation}: a run or compile is already in progress. Wait for it to ` +
        "finish (or, for a run awaiting input, supply stdin) before starting another run or compile."
      );
    }
  }

  /**
   * Compile and run the given sources.
   *
   * @param {{ files: SourceFile[], scalacOptions?: string[], diagnostics?: boolean }} request
   *   `scalacOptions` are passed to the compiler ahead of the worker-managed
   *   `-classpath`/`-d`, which win on conflict. `diagnostics` enables verbose/profiling
   *   output in `compilerLog`; structured `diagnostics` in the result are always populated.
   * @returns {Promise<RunResult>}
   */
  function run(request = {}) {
    ensureIdleForCompileOrRun("run");
    const files = Array.isArray(request.files) ? request.files : [];
    const message = {
      type: "run",
      files: files.map(({ path, content }) => ({ path, content })),
      diagnostics: Boolean(request.diagnostics),
    };
    if (Array.isArray(request.scalacOptions)) {
      message.scalacOptions = request.scalacOptions;
    }

    lastCompileDuration = null;
    return new Promise((resolve, reject) => {
      pendingRun = { resolve, reject };
      awaitingInput = false;
      worker.postMessage(message);
    });
  }

  /**
   * Compile the given sources without discovering an entry point, linking, or running
   * them — for editor-style diagnostics. Shares the single run-or-compile slot with
   * `run`; calling either while one is in flight throws synchronously.
   *
   * Compilation errors resolve with `ok: false`; invalid requests (duplicate paths,
   * paths containing `..`, non-`.scala` paths) and fatal worker errors reject.
   *
   * @param {{ files: SourceFile[], scalacOptions?: string[], diagnostics?: boolean }} request
   *   `scalacOptions` are passed to the compiler ahead of the worker-managed
   *   `-classpath`/`-d`, which win on conflict. `diagnostics` enables verbose/profiling
   *   output in `compilerLog`; structured `diagnostics` in the result are always populated.
   * @returns {Promise<CompileResult>}
   */
  function compile(request = {}) {
    ensureIdleForCompileOrRun("compile");
    const files = Array.isArray(request.files) ? request.files : [];
    const message = {
      type: "compile",
      files: files.map(({ path, content }) => ({ path, content })),
      diagnostics: Boolean(request.diagnostics),
    };
    if (Array.isArray(request.scalacOptions)) {
      message.scalacOptions = request.scalacOptions;
    }

    return new Promise((resolve, reject) => {
      pendingCompile = { resolve, reject };
      worker.postMessage(message);
    });
  }

  /**
   * Provide a line of standard input to a program that is awaiting input.
   * Only legal after `onAwaitingInput` has fired for the current run.
   *
   * @param {string} line
   */
  function sendStdin(line) {
    if (terminated) {
      throw new Error("The compiler client has been terminated.");
    }
    if (!pendingRun || !awaitingInput) {
      throw new Error("sendStdin is only valid while a run is awaiting input.");
    }
    awaitingInput = false;
    worker.postMessage({ type: "stdin", line: String(line ?? "") });
  }

  /**
   * Import compiled macro artifacts (jars / sjsir zips) so subsequent runs can
   * expand macros they define. The `bytes` buffers are transferred to the worker.
   *
   * @param {MacroArtifact[]} files
   * @returns {Promise<MacroImportResult>}
   */
  function importMacroArtifacts(files) {
    if (terminated) {
      throw new Error("The compiler client has been terminated.");
    }
    if (pendingImport) {
      throw new Error("A macro import is already in progress.");
    }
    const importFiles = (Array.isArray(files) ? files : []).map((file) => ({
      name: file.name,
      bytes: file.bytes,
    }));
    // Transfer each backing ArrayBuffer at most once: two Uint8Arrays can share
    // one buffer, and listing a buffer twice in the transfer list throws.
    const transfer = [];
    const seenBuffers = new Set();
    for (const file of importFiles) {
      const buffer = file.bytes?.buffer;
      if (buffer && !seenBuffers.has(buffer)) {
        seenBuffers.add(buffer);
        transfer.push(buffer);
      }
    }

    return new Promise((resolve, reject) => {
      pendingImport = { resolve, reject };
      worker.postMessage({ type: "import-macro-artifact", files: importFiles }, transfer);
    });
  }

  /**
   * Terminate the worker and reject every pending promise. The client cannot be
   * reused afterwards; create a new one to compile again.
   */
  function terminate() {
    if (terminated) {
      return;
    }
    terminated = true;
    worker.terminate();
    rejectAllPending(new Error("The compiler client has been terminated."));
  }

  return { ready, run, compile, sendStdin, importMacroArtifacts, terminate };
}
