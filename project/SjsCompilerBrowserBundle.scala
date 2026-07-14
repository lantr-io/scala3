import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

import sbt.*

import scala.sys.process.{Process => SysProcess, ProcessLogger}
import scala.util.control.NonFatal

/** Packages the fullLinkJS output of scala3-compiler-sjs into an npm tarball
 *  (`scala3-compiler-sjs-browser`) that lets a third-party web app run the
 *  Scala 3 compiler inline in a browser Web Worker.
 *
 *  The compiler module (`main.js`/`__loader.js`/`main.wasm`) must keep those
 *  exact file names and stay siblings, so immutability comes from a hashed
 *  DIRECTORY name (`compiler-<H1>/`) rather than from renaming the files. All
 *  other assets (jars, IR zips, the jszip vendor bundle) are content-hashed
 *  into their file or directory names and referenced through a manifest.json
 *  whose URLs are relative to itself; worker.js absolutizes them at load time.
 */
object SjsCompilerBrowserBundle {
  private val PackageName = "scala3-compiler-sjs-browser"

  def build(
      bundleDir: File,
      linkedDir: File,
      browserIdeDir: File,
      compilerIRZip: File,
      runtimeIRZip: File,
      rtJar: File,
      scalaLibJar: File,
      scalaJSLibJar: File,
      jszipDist: File,
      compilerVersion: String,
      repoBaseDir: File,
      log: Logger,
  ): File = {
    val compilerMain = linkedDir / "main.js"
    val compilerWasm = linkedDir / "main.wasm"
    val compilerLoader = linkedDir / "__loader.js"
    if (!compilerMain.exists() || !compilerWasm.exists() || !compilerLoader.exists())
      sys.error(s"Missing scala3-compiler-sjs fullLink output in $linkedDir. Run fullLinkJS first.")

    Seq(rtJar, scalaLibJar, scalaJSLibJar, compilerIRZip, runtimeIRZip, jszipDist).foreach { file =>
      if (!file.isFile)
        sys.error(s"Missing input for the browser bundle: $file")
    }

    val clientJs = browserIdeDir / "client.js"
    val workerJs = browserIdeDir / "worker.js"
    val memoryFsJs = browserIdeDir / "memory-fs.js"
    val readme = browserIdeDir / "npm" / "README.md"
    Seq(clientJs, workerJs, memoryFsJs, readme).foreach { file =>
      if (!file.isFile)
        sys.error(s"Missing browser bundle source file: $file")
    }

    val node = findCommand("node").getOrElse(sys.error("Could not find `node` on PATH."))
    val npm = findCommand("npm").getOrElse(sys.error("Could not find `npm` on PATH."))

    // Content hashes. H2 keys the jszip vendor dir; H1 keys the compiler dir and
    // is derived from the RAW (unpatched) main.js plus the wasm plus H2, so it
    // changes whenever any compiler asset or the vendored jszip changes.
    val rawMainBytes = Files.readAllBytes(compilerMain.toPath)
    val wasmBytes = Files.readAllBytes(compilerWasm.toPath)
    val h2 = sha256Hex(Files.readAllBytes(jszipDist.toPath)).take(16)
    val h1 = sha256Hex(rawMainBytes, wasmBytes, h2.getBytes("UTF-8")).take(16)
    val rtHash = fileHash(rtJar)
    val scalaLibHash = fileHash(scalaLibJar)
    val scalaJSLibHash = fileHash(scalaJSLibJar)
    val runtimeHash = fileHash(runtimeIRZip)

    val compilerDirName = s"compiler-$h1"
    val vendorDirName = s"vendor-$h2"

    // Patch main.js: rewrite the bare `jszip` import to the vendored wrapper,
    // which lives in the sibling vendor dir one level up from the compiler dir.
    val patchedMain = {
      val original = IO.read(compilerMain)
      val replacement = s"""import * as importedjszip from "../$vendorDirName/jszip-wrapper.js";"""
      val patched = original.replace(SjsCompilerBrowserIDE.CompilerJSImport, replacement)
      if (patched == original)
        sys.error(s"Could not rewrite JSZip import in ${compilerMain.getAbsolutePath}")
      patched
    }
    // Patch __loader.js: the same robust wasm load rewrite as the browser IDE.
    val patchedLoader = {
      val original = IO.read(compilerLoader)
      val patched = original.replace(
        SjsCompilerBrowserIDE.CompilerWasmStreamingLoad,
        SjsCompilerBrowserIDE.CompilerWasmRobustLoad,
      )
      if (patched == original)
        sys.error(s"Could not rewrite WebAssembly loader in ${compilerLoader.getAbsolutePath}")
      patched
    }

    // Stage the package layout fresh on every build.
    val packageDir = bundleDir / "package"
    IO.delete(packageDir)
    val distDir = packageDir / "dist"
    val compilerDir = distDir / compilerDirName
    val vendorDir = distDir / vendorDirName
    val classpathDir = distDir / "classpath"
    val runtimeDir = distDir / "runtime"
    Seq(compilerDir, vendorDir, classpathDir, runtimeDir).foreach(IO.createDirectory)

    val rtJarName = s"rt-$rtHash.jar"
    val scalaLibJarName = s"scala-lib-$scalaLibHash.jar"
    val scalaJSLibJarName = s"scalajs-lib-$scalaJSLibHash.jar"
    val runtimeZipName = s"runtime-sjsir-$runtimeHash.zip"

    IO.write(compilerDir / "main.js", patchedMain)
    IO.write(compilerDir / "__loader.js", patchedLoader)
    Seq(
      compilerWasm -> (compilerDir / "main.wasm"),
      compilerIRZip -> (compilerDir / "compiler-sjsir.zip"),
      jszipDist -> (vendorDir / "jszip.global.js"),
      rtJar -> (classpathDir / rtJarName),
      scalaLibJar -> (classpathDir / scalaLibJarName),
      scalaJSLibJar -> (classpathDir / scalaJSLibJarName),
      runtimeIRZip -> (runtimeDir / runtimeZipName),
    ).foreach { case (src, dest) => IO.copyFile(src, dest) }

    IO.write(
      vendorDir / "jszip-wrapper.js",
      """import "./jszip.global.js";
        |export default globalThis.JSZip;
        |""".stripMargin,
    )

    // Copy the shipped JS + README from the browser IDE sources.
    IO.copyFile(clientJs, packageDir / "client.js")
    IO.copyFile(workerJs, packageDir / "worker.js")
    IO.copyFile(memoryFsJs, packageDir / "memory-fs.js")
    IO.copyFile(readme, packageDir / "README.md")

    val gitSha = resolveGitSha(repoBaseDir, log)

    // manifest.json — URLs are relative to the manifest (it lives in dist/).
    val compilerModuleUrl = s"./$compilerDirName/main.js"
    val compilerIRUrl = s"./$compilerDirName/compiler-sjsir.zip"
    val runtimeIRUrl = s"./runtime/$runtimeZipName"
    val jszipWrapperUrl = s"./$vendorDirName/jszip-wrapper.js"
    val classpathEntries = Seq(
      "/lib/rt.jar" -> s"./classpath/$rtJarName",
      "/lib/scala-lib.jar" -> s"./classpath/$scalaLibJarName",
      "/lib/scalajs-lib.jar" -> s"./classpath/$scalaJSLibJarName",
    )
    val classpathJson = classpathEntries
      .map { case (path, url) => s"""    { "path": ${jsonString(path)}, "url": ${jsonString(url)} }""" }
      .mkString("[\n", ",\n", "\n  ]")
    val manifest =
      s"""{
         |  "schemaVersion": 1,
         |  "compilerModule": ${jsonString(compilerModuleUrl)},
         |  "compilerIR": ${jsonString(compilerIRUrl)},
         |  "runtimeIR": ${jsonString(runtimeIRUrl)},
         |  "jszipWrapper": ${jsonString(jszipWrapperUrl)},
         |  "assetVersion": ${jsonString(h1)},
         |  "compilerVersion": ${jsonString(compilerVersion)},
         |  "gitSha": ${jsonString(gitSha)},
         |  "classpath": $classpathJson
         |}
         |""".stripMargin
    val manifestFile = distDir / "manifest.json"
    IO.write(manifestFile, manifest)

    // package.json — content fixed by the packaging contract.
    val filesEntries = Seq("client.js", "worker.js", "memory-fs.js", "dist", "README.md")
    val packageJson =
      s"""{
         |  "name": ${jsonString(PackageName)},
         |  "version": ${jsonString(compilerVersion)},
         |  "description": "Scala 3 compiler compiled to JS/WebAssembly, runnable inline in a browser Web Worker",
         |  "type": "module",
         |  "main": "./client.js",
         |  "exports": {
         |    ".": "./client.js",
         |    "./client": "./client.js",
         |    "./worker": "./worker.js",
         |    "./memory-fs": "./memory-fs.js",
         |    "./dist/*": "./dist/*",
         |    "./package.json": "./package.json"
         |  },
         |  "sideEffects": ["./worker.js"],
         |  "files": [${filesEntries.map(jsonString).mkString(", ")}],
         |  "license": "Apache-2.0",
         |  "scala3sjs": {
         |    "gitSha": ${jsonString(gitSha)},
         |    "assetVersion": ${jsonString(h1)},
         |    "compilerVersion": ${jsonString(compilerVersion)}
         |  }
         |}
         |""".stripMargin
    IO.write(packageDir / "package.json", packageJson)

    // Structural validation — fail the build on any violation.
    val manifestUri = manifestFile.toURI
    val manifestUrls =
      Seq(
        "compilerModule" -> compilerModuleUrl,
        "compilerIR" -> compilerIRUrl,
        "runtimeIR" -> runtimeIRUrl,
        "jszipWrapper" -> jszipWrapperUrl,
      ) ++ classpathEntries.map { case (path, url) => s"classpath[$path]" -> url }
    manifestUrls.foreach { case (key, url) =>
      val resolved = new File(manifestUri.resolve(url))
      if (!resolved.isFile)
        sys.error(s"manifest.json URL for $key ($url) does not resolve to a staged file: $resolved")
    }

    Seq(packageDir / "client.js", packageDir / "worker.js", packageDir / "memory-fs.js").foreach { js =>
      runProcess(Seq(node, "--check", js.getAbsolutePath), s"node --check ${js.getName}", None, log)
    }

    filesEntries.foreach { entry =>
      if (!(packageDir / entry).exists())
        sys.error(s"package.json `files` entry does not exist: $entry")
    }

    // Pack the tarball. Clean stale tarballs first so the result glob is unambiguous.
    IO.createDirectory(bundleDir)
    tgzFiles(bundleDir).foreach(IO.delete)
    runProcess(
      Seq(npm, "pack", "--pack-destination", bundleDir.getAbsolutePath),
      "npm pack",
      Some(packageDir),
      log,
    )
    val tarball = tgzFiles(bundleDir).headOption.getOrElse(
      sys.error(s"npm pack did not produce a .tgz in $bundleDir")
    )

    val sizeMB = tarball.length().toDouble / (1024 * 1024)
    log.info(f"Packaged browser bundle: ${tarball.getAbsolutePath} ($sizeMB%.2f MB)")
    tarball
  }

  private def tgzFiles(dir: File): Seq[File] =
    Option(dir.listFiles()).map(_.toSeq).getOrElse(Nil).filter(f => f.isFile && f.getName.endsWith(".tgz"))

  private def resolveGitSha(repoBaseDir: File, log: Logger): String =
    try {
      val output = new StringBuilder
      val exit = SysProcess(Seq("git", "rev-parse", "--short", "HEAD"), repoBaseDir).!(ProcessLogger(
        line => output.append(line).append('\n'),
        _ => (),
      ))
      val sha = output.toString.trim
      if (exit == 0 && sha.nonEmpty) sha else "unknown"
    } catch {
      case NonFatal(_) =>
        log.warn("Could not determine git SHA for the browser bundle; using \"unknown\".")
        "unknown"
    }

  private def fileHash(file: File): String =
    sha256Hex(Files.readAllBytes(file.toPath)).take(16)

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

  private def runProcess(command: Seq[String], label: String, cwd: Option[File], log: Logger): String = {
    val output = new StringBuilder
    log.info(s"Running $label")
    log.debug(command.mkString(" ", " ", ""))
    val builder = cwd match {
      case Some(dir) => SysProcess(command, dir)
      case None => SysProcess(command)
    }
    val exit = builder.!(ProcessLogger(
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

  private def jsonString(value: String): String =
    value.flatMap {
      case '"' => "\\\""
      case '\\' => "\\\\"
      case c if c < ' ' => f"\\u${c.toInt}%04x"
      case c => c.toString
    }.mkString("\"", "", "\"")
}
