#!/usr/bin/env node
// End-to-end test for the scala3-compiler-sjs-browser package.
//
//   node run-e2e.mjs <packageDir> [chromePath]
//
// Statically serves the (unpacked) package directory plus e2e/page.html, launches
// headless Chrome with WebAssembly JSPI enabled, and waits for the page to POST its
// result. Exits 0 on success (or when no Chrome is available), 1 on failure.
//
// Plain Node builtins only — no npm dependencies. Mirrors the sbt-side harness in
// project/SjSCompilerHelper.scala (SjsCompilerValidationTest).
import http from "node:http";
import { spawn } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import os from "node:os";
import { fileURLToPath } from "node:url";

const RESULT_TIMEOUT_MS = 300000;
const CHROME_OUTPUT_TAIL_CHARS = 4000;
const e2eDir = path.dirname(fileURLToPath(import.meta.url));

const CONTENT_TYPES = {
  ".js": "text/javascript; charset=utf-8",
  ".mjs": "text/javascript; charset=utf-8",
  ".html": "text/html; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".json": "application/json; charset=utf-8",
  ".wasm": "application/wasm",
  ".jar": "application/zip",
  ".zip": "application/zip",
};

function contentType(filePath) {
  return CONTENT_TYPES[path.extname(filePath).toLowerCase()] ?? "application/octet-stream";
}

function tail(text, maxChars) {
  return text.length > maxChars ? text.slice(-maxChars) : text;
}

function which(command) {
  for (const dir of (process.env.PATH ?? "").split(path.delimiter)) {
    if (!dir) {
      continue;
    }
    const candidate = path.join(dir, command);
    try {
      fs.accessSync(candidate, fs.constants.X_OK);
      return candidate;
    } catch (_) {
      // keep looking
    }
  }
  return null;
}

function resolveChrome(explicit) {
  if (explicit) {
    return explicit;
  }
  const commands = ["google-chrome", "google-chrome-stable", "chromium", "chromium-browser", "chrome"];
  for (const command of commands) {
    const found = which(command);
    if (found) {
      return found;
    }
  }
  const macOsBundles = [
    "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
    "/Applications/Chromium.app/Contents/MacOS/Chromium",
  ];
  for (const bundle of macOsBundles) {
    if (fs.existsSync(bundle)) {
      return bundle;
    }
  }
  return null;
}

function fail(message) {
  console.error(message);
  process.exit(1);
}

async function main() {
  const packageArg = process.argv[2];
  if (!packageArg) {
    fail("usage: node run-e2e.mjs <packageDir> [chromePath]");
  }
  const packageDir = path.resolve(packageArg);
  if (!fs.existsSync(packageDir) || !fs.statSync(packageDir).isDirectory()) {
    fail(`[e2e] package directory not found: ${packageDir}`);
  }
  for (const required of ["client.js", "worker.js", "dist/manifest.json"]) {
    if (!fs.existsSync(path.join(packageDir, required))) {
      fail(`[e2e] package directory is missing ${required}: ${packageDir}`);
    }
  }

  const chrome = resolveChrome(process.argv[3] || process.env.CHROME_BIN);
  if (!chrome) {
    // Not a failure: environments without a browser (e.g. minimal CI) skip cleanly.
    console.log("E2E-SKIP: no Chrome available");
    process.exit(0);
  }

  let settled = false;
  let resolveOutcome;
  const outcomePromise = new Promise((resolve) => {
    resolveOutcome = resolve;
  });
  function settle(outcome) {
    if (!settled) {
      settled = true;
      resolveOutcome(outcome);
    }
  }

  const server = http.createServer((req, res) => {
    const requestUrl = new URL(req.url, "http://127.0.0.1");
    const pathname = decodeURIComponent(requestUrl.pathname);

    if (req.method === "POST" && pathname === "/__result") {
      let body = "";
      req.on("data", (chunk) => {
        body += chunk;
      });
      req.on("end", () => {
        res.writeHead(200, { "content-type": "text/plain; charset=utf-8", "cache-control": "no-store" });
        res.end("ok");
        settle({ type: "result", body });
      });
      return;
    }

    if (req.method !== "GET" && req.method !== "HEAD") {
      res.writeHead(405, { "cache-control": "no-store" });
      res.end("method not allowed");
      return;
    }

    let filePath;
    if (pathname === "/e2e.html") {
      filePath = path.join(e2eDir, "page.html");
    } else {
      const relative = pathname === "/" ? "index.html" : pathname.replace(/^\/+/, "");
      const resolved = path.normalize(path.join(packageDir, relative));
      if (resolved !== packageDir && !resolved.startsWith(packageDir + path.sep)) {
        res.writeHead(403, { "cache-control": "no-store" });
        res.end("forbidden");
        return;
      }
      filePath = resolved;
    }

    fs.readFile(filePath, (error, data) => {
      if (error) {
        res.writeHead(404, { "cache-control": "no-store" });
        res.end("not found");
        return;
      }
      res.writeHead(200, { "content-type": contentType(filePath), "cache-control": "no-store" });
      if (req.method === "HEAD") {
        res.end();
      } else {
        res.end(data);
      }
    });
  });

  await new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", resolve);
  });
  const port = server.address().port;
  const pageUrl = `http://127.0.0.1:${port}/e2e.html`;

  console.log(`[e2e] package:  ${packageDir}`);
  console.log(`[e2e] chrome:   ${chrome}`);
  console.log(`[e2e] serving:  http://127.0.0.1:${port}/  ->  ${pageUrl}`);

  const userDataDir = fs.mkdtempSync(path.join(os.tmpdir(), "scala3-e2e-chrome-"));
  const chromeArgs = [
    "--headless=new",
    "--disable-gpu",
    "--disable-dev-shm-usage",
    "--no-sandbox",
    `--user-data-dir=${userDataDir}`,
    "--enable-features=WebAssemblyJSPI",
    pageUrl,
  ];
  console.log(`[e2e] launching Chrome (headless, JSPI enabled)...`);

  let chromeOutput = "";
  const child = spawn(chrome, chromeArgs, { stdio: ["ignore", "pipe", "pipe"] });
  child.stdout.on("data", (chunk) => {
    chromeOutput += chunk;
  });
  child.stderr.on("data", (chunk) => {
    chromeOutput += chunk;
  });
  child.on("error", (error) => settle({ type: "chrome-error", error }));
  child.on("exit", (code, signal) => settle({ type: "chrome-exit", code, signal }));

  const timeout = setTimeout(() => settle({ type: "timeout" }), RESULT_TIMEOUT_MS);

  const outcome = await outcomePromise;
  clearTimeout(timeout);

  try {
    if (child.exitCode === null && child.signalCode === null) {
      child.kill("SIGKILL");
    }
  } catch (_) {
    // process already gone
  }
  await new Promise((resolve) => server.close(resolve));
  try {
    fs.rmSync(userDataDir, { recursive: true, force: true });
  } catch (_) {
    // best-effort cleanup
  }

  if (outcome.type === "result") {
    let parsed = null;
    try {
      parsed = JSON.parse(outcome.body);
    } catch (_) {
      parsed = null;
    }
    if (parsed && parsed.ok === true) {
      console.log("[e2e] PASS: compiler ran hello-from-e2e in headless Chrome");
      printPageLog(parsed.log, console.log);
      process.exit(0);
    }
    console.error("[e2e] FAIL: page reported a failure");
    if (parsed) {
      if (parsed.error) {
        console.error(`[e2e] page error: ${parsed.error}`);
      }
      printPageLog(parsed.log, console.error);
    } else {
      console.error(`[e2e] unparseable result body: ${outcome.body}`);
    }
    printChromeTail(chromeOutput);
    process.exit(1);
  }

  if (outcome.type === "chrome-exit") {
    console.error(`[e2e] FAIL: Chrome exited (code=${outcome.code}, signal=${outcome.signal}) before reporting a result`);
    printChromeTail(chromeOutput);
    process.exit(1);
  }

  if (outcome.type === "chrome-error") {
    console.error(`[e2e] FAIL: could not launch Chrome: ${outcome.error?.message ?? outcome.error}`);
    printChromeTail(chromeOutput);
    process.exit(1);
  }

  console.error(`[e2e] FAIL: timed out after ${RESULT_TIMEOUT_MS / 1000}s waiting for the page result`);
  printChromeTail(chromeOutput);
  process.exit(1);
}

function printPageLog(log, sink) {
  if (Array.isArray(log)) {
    for (const line of log) {
      sink(`  [page] ${line}`);
    }
  }
}

function printChromeTail(chromeOutput) {
  const trimmed = chromeOutput.trim();
  if (trimmed) {
    console.error(`\n[e2e] last ${CHROME_OUTPUT_TAIL_CHARS} chars of Chrome output:\n${tail(trimmed, CHROME_OUTPUT_TAIL_CHARS)}`);
  }
}

main().catch((error) => {
  console.error(`[e2e] FAIL: ${error?.stack ?? error}`);
  process.exit(1);
});
