import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

import sbt.*

import scala.sys.process.{Process => SysProcess, ProcessLogger}

/** Packages the fullLinkJS output of scala3-compiler-sjs into a Node.js
 *  Single Executable Application (SEA).
 *
 *  A SEA can only embed a CommonJS main script, while the linked compiler is
 *  an ES module with top-level await that loads `main.wasm` relative to its
 *  own location and bare-imports `jszip`. The embedded launcher therefore
 *  extracts all assets to a per-build cache directory on first run and loads
 *  `main.js` from there. The load happens in a worker thread because the SEA
 *  main script cannot use dynamic import() itself: the embedder module loader
 *  only resolves builtins (ERR_UNKNOWN_BUILTIN_MODULE), whereas a worker runs
 *  with the standard ESM loader.
 */
object SjsCompilerSEA {
  private val BinaryName = "scala3-sjs"
  private val SeaFuseSentinel = "NODE_SEA_FUSE_fce680ab2cc467b6e072b8b5df1996b2"

  private val SeaLauncher =
    """'use strict';
      |const sea = require('node:sea');
      |const fs = require('node:fs');
      |const os = require('node:os');
      |const path = require('node:path');
      |
      |const ASSETS = [
      |  'main.js',
      |  '__loader.js',
      |  'main.wasm',
      |  'node_modules/jszip/package.json',
      |  'node_modules/jszip/dist/jszip.min.js',
      |  'classpath/rt.jar',
      |  'classpath/scala-lib.jar',
      |  'classpath/scalajs-lib.jar',
      |];
      |
      |function extractAssets(cacheDir) {
      |  for (const name of ASSETS) {
      |    const dest = path.join(cacheDir, name);
      |    if (fs.existsSync(dest)) continue;
      |    fs.mkdirSync(path.dirname(dest), { recursive: true });
      |    const tmp = dest + '.' + process.pid + '.tmp';
      |    fs.writeFileSync(tmp, Buffer.from(sea.getRawAsset(name)));
      |    fs.renameSync(tmp, dest);
      |  }
      |}
      |
      |const WORKER_SOURCE = [
      |  "import { parentPort, workerData } from 'node:worker_threads';",
      |  "import { pathToFileURL } from 'node:url';",
      |  "const compiler = await import(pathToFileURL(workerData.mainJs).href);",
      |  "const code = await compiler.runScala3CompilerSJSAsync(workerData.args);",
      |  "parentPort.postMessage(code);",
      |].join('\n');
      |
      |function main() {
      |  const buildId = sea.getAsset('build-id', 'utf8').trim();
      |  const cacheDir = path.join(os.homedir(), '.cache', 'scala3-compiler-sjs', buildId);
      |  extractAssets(cacheDir);
      |
      |  const workerPath = path.join(cacheDir, 'sea-worker.mjs');
      |  const workerTmp = workerPath + '.' + process.pid + '.tmp';
      |  fs.writeFileSync(workerTmp, WORKER_SOURCE);
      |  fs.renameSync(workerTmp, workerPath);
      |
      |  // In a SEA, argv[0] and argv[1] are both the executable path; user args start at 2.
      |  const args = process.argv.slice(2);
      |  if (!args.includes('-classpath') && !args.includes('-cp')) {
      |    const cp = ['rt.jar', 'scala-lib.jar', 'scalajs-lib.jar']
      |      .map((jar) => path.join(cacheDir, 'classpath', jar))
      |      .join(path.delimiter);
      |    args.unshift('-classpath', cp);
      |  }
      |
      |  const { Worker } = require('node:worker_threads');
      |  const worker = new Worker(workerPath, {
      |    workerData: { mainJs: path.join(cacheDir, 'main.js'), args },
      |  });
      |  let exitCode = null;
      |  worker.on('message', (code) => { exitCode = code; });
      |  worker.on('error', (err) => {
      |    console.error(err);
      |    process.exit(1);
      |  });
      |  worker.on('exit', (code) => {
      |    process.exit(exitCode === null ? (code || 1) : exitCode);
      |  });
      |}
      |
      |try {
      |  main();
      |} catch (err) {
      |  console.error(err);
      |  process.exit(1);
      |}
      |""".stripMargin

  def build(
      seaDir: File,
      linkedDir: File,
      rtJar: File,
      scalaLibJar: File,
      scalaJSLibJar: File,
      jszipModuleDir: File,
      log: Logger,
  ): File = {
    val osName = System.getProperty("os.name").toLowerCase
    val isMac = osName.contains("mac")
    if (osName.contains("windows"))
      sys.error("buildSEA currently supports only macOS and Linux.")

    val compilerMain = linkedDir / "main.js"
    val compilerWasm = linkedDir / "main.wasm"
    val compilerLoader = linkedDir / "__loader.js"
    if (!compilerMain.exists() || !compilerWasm.exists() || !compilerLoader.exists())
      sys.error(s"Missing scala3-compiler-sjs fullLink output in $linkedDir. Run fullLinkJS first.")

    val jszipPackageJson = jszipModuleDir / "package.json"
    val jszipDistMin = jszipModuleDir / "dist" / "jszip.min.js"
    if (!jszipPackageJson.exists() || !jszipDistMin.exists())
      sys.error("Missing Node dependency `jszip`. Run `cd compiler && npm install` before building the SEA binary.")

    Seq(rtJar, scalaLibJar, scalaJSLibJar).foreach { jar =>
      if (!jar.isFile)
        sys.error(s"Missing classpath jar for the SEA binary: $jar")
    }

    val node = findCommand("node").getOrElse(sys.error("Could not find `node` on PATH."))
    val npx = findCommand("npx").getOrElse(sys.error("Could not find `npx` on PATH."))

    // The wasm flags in sjsCompilerNodeFlags cannot be passed to a SEA binary,
    // so the features must be enabled by default in the embedded Node.
    val jspiProbe = runProcess(Seq(node, "-p", "typeof WebAssembly.Suspending"), "node wasm feature probe", log)
      .linesIterator.map(_.trim).filter(_.nonEmpty).toSeq.lastOption.getOrElse("")
    if (jspiProbe != "function")
      sys.error(
        s"The `node` on PATH ($node) does not enable WebAssembly JSPI by default (probe returned `$jspiProbe`). " +
          "Node >= 25 is required to build the SEA binary."
      )

    IO.createDirectory(seaDir)

    // jszip's real entry point (lib/index.js) pulls in its full npm dependency
    // tree; embed the self-contained dist bundle behind a minimal package.json
    // instead.
    val jszipVersion = """"version"\s*:\s*"([^"]+)"""".r
      .findFirstMatchIn(IO.read(jszipPackageJson)).map(_.group(1)).getOrElse("0.0.0")
    val jszipSeaPackageContents = s"""{"name":"jszip","version":"$jszipVersion","main":"./dist/jszip.min.js"}\n"""
    val jszipSeaPackageJson = seaDir / "jszip-package.json"
    IO.write(jszipSeaPackageJson, jszipSeaPackageContents)

    // The build id keys the extraction cache dir, so it must change whenever
    // the wasm, the launcher, or the packaging layout changes.
    val buildIdFile = seaDir / "build-id"
    val buildId = sha256Hex(
      Files.readAllBytes(compilerWasm.toPath),
      SeaLauncher.getBytes("UTF-8"),
      jszipSeaPackageContents.getBytes("UTF-8"),
    ).take(16)
    IO.write(buildIdFile, buildId + "\n")

    val launcher = seaDir / "sea-main.cjs"
    IO.write(launcher, SeaLauncher)

    val blob = seaDir / "sea-prep.blob"
    val assets: Seq[(String, File)] = Seq(
      "build-id" -> buildIdFile,
      "main.js" -> compilerMain,
      "__loader.js" -> compilerLoader,
      "main.wasm" -> compilerWasm,
      "node_modules/jszip/package.json" -> jszipSeaPackageJson,
      "node_modules/jszip/dist/jszip.min.js" -> jszipDistMin,
      "classpath/rt.jar" -> rtJar,
      "classpath/scala-lib.jar" -> scalaLibJar,
      "classpath/scalajs-lib.jar" -> scalaJSLibJar,
    )
    val seaConfig = seaDir / "sea-config.json"
    IO.write(seaConfig, seaConfigJson(launcher, blob, assets))

    runProcess(Seq(node, "--experimental-sea-config", seaConfig.getAbsolutePath), "Node SEA blob generation", log)

    val binary = seaDir / BinaryName
    IO.copyFile(new File(node), binary)
    binary.setExecutable(true, false)

    if (isMac)
      runProcess(Seq("codesign", "--remove-signature", binary.getAbsolutePath), "codesign signature removal", log)

    val postjectCommand =
      Seq(npx, "--yes", "postject", binary.getAbsolutePath, "NODE_SEA_BLOB", blob.getAbsolutePath,
        "--sentinel-fuse", SeaFuseSentinel) ++
        (if (isMac) Seq("--macho-segment-name", "NODE_SEA") else Nil)
    runProcess(postjectCommand, "postject SEA blob injection", log)

    if (isMac)
      runProcess(Seq("codesign", "--sign", "-", binary.getAbsolutePath), "codesign ad-hoc signing", log)

    runProcess(Seq(binary.getAbsolutePath, "-version"), s"$BinaryName SEA smoke test", log)

    log.info(s"Built Node SEA binary: $binary")
    binary
  }

  private def seaConfigJson(main: File, output: File, assets: Seq[(String, File)]): String = {
    val assetEntries = assets
      .map { case (key, file) => s"    ${jsonString(key)}: ${jsonString(file.getAbsolutePath)}" }
      .mkString(",\n")
    s"""{
       |  "main": ${jsonString(main.getAbsolutePath)},
       |  "output": ${jsonString(output.getAbsolutePath)},
       |  "disableExperimentalSEAWarning": true,
       |  "assets": {
       |$assetEntries
       |  }
       |}
       |""".stripMargin
  }

  private def jsonString(value: String): String =
    value.flatMap {
      case '"' => "\\\""
      case '\\' => "\\\\"
      case c if c < ' ' => f"\\u${c.toInt}%04x"
      case c => c.toString
    }.mkString("\"", "", "\"")

  private def sha256Hex(chunks: Array[Byte]*): String = {
    val digest = MessageDigest.getInstance("SHA-256")
    chunks.foreach(digest.update)
    digest.digest().map(b => f"$b%02x").mkString
  }

  private def findCommand(command: String): Option[String] = {
    val output = new StringBuilder
    val exit = SysProcess(Seq("sh", "-lc", s"command -v $command")).!(ProcessLogger(
      line => output.append(line).append('\n'),
      _ => (),
    ))
    if (exit == 0) {
      val path = output.toString.trim
      if (path.nonEmpty) Some(path) else None
    } else None
  }

  private def runProcess(command: Seq[String], label: String, log: Logger): String = {
    val output = new StringBuilder
    log.info(s"Running $label")
    log.debug(command.mkString(" ", " ", ""))
    val exit = SysProcess(command).!(ProcessLogger(
      line => {
        output.append(line).append('\n')
        log.info(line)
      },
      line => {
        output.append(line).append('\n')
        log.warn(line)
      }
    ))
    val text = output.toString
    if (exit != 0)
      sys.error(s"$label failed with exit code $exit:\n$text")
    text
  }
}
