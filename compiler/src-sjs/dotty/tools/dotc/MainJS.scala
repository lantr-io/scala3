package dotty.tools
package dotc

import dotty.tools.dotc.sjsmacros.{MacroRuntimeExports, MacroRuntimeRegistry, MissingMacroEntryPointException}
import dotty.tools.dotc.sjsmacros.host.SjsBrowserMacroLinker
import dotty.tools.dotc.sbt.interfaces.ProgressCallback
import dotty.tools.dotc.core.MacroClassPathScanner
import dotty.tools.dotc.core.Contexts.*
import dotty.tools.io.JSByteArrays
import scala.scalajs.js
import scala.scalajs.js.annotation.{JSGlobal, JSExportTopLevel}
import scala.scalajs.js.JSConverters.*
import scala.scalajs.js.wasm.JSPI.allowOrphanJSAwait
import scala.collection.mutable
import scala.util.control.NonFatal

/** JS entry point of the `dotc` batch compiler. */
object MainJS extends JSDriver:
  @js.native
  @JSGlobal("globalThis")
  private object GlobalThis extends js.Object

  // Internal interrupt code used to relink macro entry points, not a process exit status.
  private val MissingMacroEntryPointExitCode = 125
  // A runaway macro relink loop usually means a macro artifact is missing or stale.
  private val DefaultMaxMacroLinkRestarts = 8
  private val MaxBrowserCompilerSessions = 4
  private val MacroSessionExport = "runScala3SjsMacroSessionAsync"
  private val StructuredMacroSessionExport = "runScala3SjsMacroSessionStructuredAsync"
  private val PublicModuleUrlsGlobalName = "__scala3CompilerSJSPublicModuleUrls"

  private type MacroSessionFn = js.Function5[
    js.Array[String],
    js.Array[String],
    js.Function1[js.Dynamic, js.Any],
    Int,
    js.Function2[String, Double, Unit],
    js.Promise[Int],
  ]

  private type StructuredMacroSessionFn = js.Function6[
    js.Array[String],
    js.Array[String],
    js.Function1[js.Dynamic, js.Any],
    Int,
    js.Function2[String, Double, Unit],
    js.Function1[js.Any, Unit],
    js.Promise[Int],
  ]
  private val importedModuleNamespaces = mutable.LinkedHashMap.empty[String, js.Dynamic]
  private val browserCompilerSessions = mutable.LinkedHashMap.empty[String, BrowserCompilerSession]
  private var lastMissingMacroEntryPointsOrNull: MissingMacroEntryPointException | Null = null
  private val browserMacroRelink: js.Function1[js.Dynamic, js.Any] =
    (request: js.Dynamic) => SjsBrowserMacroLinker.relink(request)

  @JSExportTopLevel("runScala3CompilerSJSAsync")
  def runAsync(args: js.Array[String]): js.Promise[Int] =
    js.async {
      runImpl(args.toArray, null)
    }

  // Build-time macro prelinking (scalus/prelink-macros.mjs): emits entry points for the given packages'
  // macros, plus the implementation IR they need (drawn from `available`, i.e. classes the compiler
  // does not already contain). Linked into the compiler, these expand without relinking in the
  // browser. A macro whose implementation reaches a class in `jvmOnly` is left out: it cannot run
  // in the browser anyway. Returns the IR files, and the ids of the macros left out.
  @JSExportTopLevel("prelinkScala3MacroIRAsync")
  def prelinkMacroIRAsync(
      setupArgs: js.Array[String],
      packageNames: js.Array[String],
      availablePaths: js.Array[String],
      availableContents: js.Array[scala.scalajs.js.typedarray.Uint8Array],
      jvmOnly: js.Array[String],
  ): js.Promise[js.Dynamic] =
    js.async {
      import dotty.tools.dotc.sjsmacros.host.{SjsMacroArtifacts, SjsMacroIRClosure}
      val available = availablePaths.toSeq.zip(availableContents.toSeq)
        .map((path, bytes) => SjsMacroArtifacts.IRFile(path, JSByteArrays.toByteArray(bytes)))
      val availableByPath = available.map(f => f.path -> f).toMap
      val blocked = jvmOnly.toSet
      def closure(roots: Seq[SjsMacroArtifacts.IRFile]) = SjsMacroIRClosure.collect(roots, available)
      def ownerIR(entry: MacroClassPathScanner.MacroEntryPoint) =
        availableByPath.get(entry.implementationOwnerClassName.nameString.replace('.', '/') + ".sjsir")
      val skipped = mutable.ArrayBuffer.empty[String]
      val entryPoints = browserCompilerSession(setupArgs.toArray).inRun {
        packageNames.toSeq.flatMap { pkg =>
          val usable = MacroClassPathScanner.macroEntryPoints(pkg).filter { entry =>
            val ok = ownerIR(entry).forall(owner => !closure(Seq(owner)).exists(f => blocked(f.path)))
            if !ok then skipped += entry.id
            ok
          }
          MacroClassPathScanner.serializedMacroEntryPointsIR(pkg, usable)
        }
      }.map(e => SjsMacroArtifacts.IRFile(e.path, e.bytes))
      val files = (entryPoints ++ closure(entryPoints)).map { f =>
        js.Dynamic.literal(path = f.path, bytes = JSByteArrays.toUint8Array(f.bytes))
      }
      js.Dynamic.literal(files = files.toJSArray, skipped = skipped.toJSArray)
    }

  // Local experiment: lets Node drive the retained browser compiler session.
  @JSExportTopLevel("runScala3BrowserSessionAsync")
  def runBrowserSessionForNodeAsync(setupArgs: js.Array[String], sourcePaths: js.Array[String]): js.Promise[Int] =
    js.async {
      runBrowserSessionImpl(setupArgs.toArray, sourcePaths.toArray, null)
    }

  def runBrowserSessionWithPhaseTimingAsync(
      setupArgs: js.Array[String],
      sourcePaths: js.Array[String],
      recordPhaseTiming: js.Function2[String, Double, Unit],
  ): js.Promise[Int] =
    js.async {
      runBrowserSessionImpl(setupArgs.toArray, sourcePaths.toArray, new PhaseTimingProgressCallback(recordPhaseTiming))
    }

  def runBrowserSessionWithRetainedMacroCompilerAndPhaseTimingAsync(
      setupArgs: js.Array[String],
      sourcePaths: js.Array[String],
      recordPhaseTiming: js.Function2[String, Double, Unit],
  ): js.Promise[Int] =
    retainedMacroCompilerModule() match
      case Some(module) =>
        val run = module.selectDynamic(MacroSessionExport).asInstanceOf[
          js.Function5[
            js.Array[String],
            js.Array[String],
            js.Function1[js.Dynamic, js.Any],
            Int,
            js.Function2[String, Double, Unit],
            js.Promise[Int],
          ]
        ]
        run(setupArgs, sourcePaths, browserMacroRelink, DefaultMaxMacroLinkRestarts, recordPhaseTiming)
      case None =>
        runBrowserSessionWithMacroLinkingAsync(
          setupArgs,
          sourcePaths,
          browserMacroRelink,
          DefaultMaxMacroLinkRestarts,
          recordPhaseTiming,
        )

  /** Structured, single-shot browser compile (no macro relinking). Resolves to a compile
   *  result object; never rejects on compile errors.
   */
  def compileBrowserSessionStructuredAsync(
      setupArgs: js.Array[String],
      sourcePaths: js.Array[String],
      recordPhaseTiming: js.Function2[String, Double, Unit] | Null,
  ): js.Promise[js.Dynamic] =
    js.async {
      compileBrowserSessionStructuredImpl(setupArgs.toArray, sourcePaths.toArray, recordPhaseTiming)
    }

  /** Structured browser compile that relinks missing macro entry points. Resolves to a
   *  compile result object aggregating the completed round's diagnostics.
   */
  def compileBrowserSessionWithRetainedMacroCompilerStructuredAsync(
      setupArgs: js.Array[String],
      sourcePaths: js.Array[String],
      recordPhaseTiming: js.Function2[String, Double, Unit] | Null,
  ): js.Promise[js.Dynamic] =
    js.async {
      val collected = new js.Array[js.Dynamic]()
      val onDiagnostic: js.Function1[js.Any, Unit] =
        (diagnostic: js.Any) =>
          collected.push(diagnostic.asInstanceOf[js.Dynamic])
          ()
      try
        val exitCode =
          runRetainedMacroSessionStructured(setupArgs.toArray, sourcePaths.toArray, recordPhaseTiming, onDiagnostic)
        buildStructuredResultFromCollected(exitCode, collected)
      catch
        case ex: SessionSetupFailedException =>
          sessionSetupFailedResult(ex)
    }

  @JSExportTopLevel("runScala3SjsMacroSessionAsync")
  def runMacroSessionAsync(
      setupArgs: js.Array[String],
      sourcePaths: js.Array[String],
      relink: js.Function1[js.Dynamic, js.Any],
      maxRestarts: Int,
      recordPhaseTiming: js.Function2[String, Double, Unit],
  ): js.Promise[Int] =
    runBrowserSessionWithMacroLinkingAsync(setupArgs, sourcePaths, relink, maxRestarts, recordPhaseTiming)

  @JSExportTopLevel("compileScala3SjsAsync")
  def compileStructuredAsync(
      setupArgs: js.Array[String],
      sourcePaths: js.Array[String],
  ): js.Promise[js.Dynamic] =
    compileBrowserSessionStructuredAsync(setupArgs, sourcePaths, null)

  @JSExportTopLevel("runScala3SjsMacroSessionStructuredAsync")
  def runMacroSessionStructuredAsync(
      setupArgs: js.Array[String],
      sourcePaths: js.Array[String],
      relink: js.Function1[js.Dynamic, js.Any],
      maxRestarts: Int,
      recordPhaseTiming: js.Function2[String, Double, Unit],
      onDiagnostic: js.Function1[js.Any, Unit],
  ): js.Promise[Int] =
    js.async {
      runBrowserSessionWithMacroLinkingStructuredImpl(
        setupArgs.toArray,
        sourcePaths.toArray,
        relink,
        maxRestarts,
        recordPhaseTiming,
        onDiagnostic,
        restartIndex = 1,
      )
    }

  def clearRetainedMacroModules(): Unit =
    importedModuleNamespaces.clear()
    MacroRuntimeRegistry.clearAll()
    clearPublicModuleUrls()

  private def runBrowserSessionWithMacroLinkingAsync(
      setupArgs: js.Array[String],
      sourcePaths: js.Array[String],
      relink: js.Function1[js.Dynamic, js.Any],
      maxRestarts: Int,
      recordPhaseTiming: js.Function2[String, Double, Unit] | Null,
  ): js.Promise[Int] =
    js.async {
      runBrowserSessionWithMacroLinkingImpl(
        setupArgs.toArray,
        sourcePaths.toArray,
        relink,
        maxRestarts,
        recordPhaseTiming,
        restartIndex = 1,
      )
    }

  override def main(args: Array[String]): Unit =
    val jsArgs = args.toJSArray
    if js.typeOf(js.Dynamic.global.process) != "undefined" && js.typeOf(js.Dynamic.global.process.exit) == "function" then
      runAsync(jsArgs).`then`[Unit](
        (code: Int) =>
          js.Dynamic.global.process.exit(code)
          (),
        (err: scala.Any) =>
          js.Dynamic.global.console.error(err.asInstanceOf[js.Any])
          js.Dynamic.global.process.exit(1)
          ()
      )
      ()
    else
      runAsync(jsArgs).`then`[Unit](
        (code: Int) =>
          if code != 0 then
            throw js.JavaScriptException(s"compiler exited with code $code")
          (),
        (err: scala.Any) =>
          throw js.JavaScriptException(err.asInstanceOf[js.Any])
      )
      ()

  private def runImpl(args: Array[String], progressCallback: ProgressCallback | Null): Int =
    withMissingMacroEntryPointHandling(progressCallback):
      mainResultWithProgress(args, progressCallback)

  private def runBrowserSessionImpl(
      setupArgs: Array[String],
      sourcePaths: Array[String],
      progressCallback: ProgressCallback | Null,
  ): Int =
    withMissingMacroEntryPointHandling(progressCallback):
      browserSessionResultWithProgress(setupArgs, sourcePaths, progressCallback)

  private def withMissingMacroEntryPointHandling(progressCallback: ProgressCallback | Null)(operation: => Int): Int =
    try
      lastMissingMacroEntryPointsOrNull = null
      MacroRuntimeRegistry.clearAll()
      MacroRuntimeRegistry.setDefaultResolver(resolveLinkedMacroEntryPoint)
      operation
    catch
      case ex: MissingMacroEntryPointException =>
        lastMissingMacroEntryPointsOrNull = ex
        MissingMacroEntryPointExitCode
      case ex: SessionSetupFailedException =>
        // Surfaced structurally by the structured entry points; converted to a
        // js.JavaScriptException at the legacy boundary. Not an infrastructure error.
        throw ex
      case jse: js.JavaScriptException =>
        logJavaScriptException(jse)
        throw jse
      case NonFatal(t) =>
        logThrowable(t)
        throw t
    finally
      progressCallback match
        case callback: PhaseTimingProgressCallback => callback.finish()
        case _ => ()

  private def mainResultWithProgress(args: Array[String], progressCallback: ProgressCallback | Null): Int =
    if progressCallback == null then mainResult(args)
    else
      progressCallback match
        case callback: PhaseTimingProgressCallback =>
          callback.withCompilerRun:
            mainResultWithProgressCallback(args, callback)
        case _ =>
          mainResultWithProgressCallback(args, progressCallback)

  private def mainResultWithProgressCallback(args: Array[String], progressCallback: ProgressCallback): Int =
    val _ = NonFatal
    val compileCtx = initCtx.fresh
    compileCtx.setProgressCallback(progressCallback)
    if process(args, compileCtx).hasErrors then 1 else 0

  private def browserSessionResultWithProgress(
      setupArgs: Array[String],
      sourcePaths: Array[String],
      progressCallback: ProgressCallback | Null,
  ): Int =
    def compile(): Int =
      legacyBrowserCompilerSession(setupArgs).compile(sourcePaths, progressCallback)

    progressCallback match
      case null => compile()
      case callback: PhaseTimingProgressCallback =>
        callback.withCompilerRun(compile())
      case _ =>
        compile()

  private def runBrowserSessionStructuredImpl(
      setupArgs: Array[String],
      sourcePaths: Array[String],
      progressCallback: ProgressCallback | Null,
      reporter: reporting.CollectingReporter,
  ): Int =
    withMissingMacroEntryPointHandling(progressCallback):
      browserSessionStructuredResultWithProgress(setupArgs, sourcePaths, progressCallback, reporter)

  private def browserSessionStructuredResultWithProgress(
      setupArgs: Array[String],
      sourcePaths: Array[String],
      progressCallback: ProgressCallback | Null,
      reporter: reporting.CollectingReporter,
  ): Int =
    def compile(): Int =
      browserCompilerSession(setupArgs).compileStructured(sourcePaths, progressCallback, reporter)

    progressCallback match
      case null => compile()
      case callback: PhaseTimingProgressCallback =>
        callback.withCompilerRun(compile())
      case _ =>
        compile()

  private final class BrowserCompilerSession(setupArgs: Array[String]):
    private val rootCtx =
      val ctx = initCtx.fresh
      ctx.base.retainPlatformBetweenRuns = true
      ctx.setReporter(new reporting.StoreReporter(null))

    /** Diagnostics reported while distilling/checking the compiler flags. They are replayed
     *  through each round's reporter so both console and structured paths observe them and
     *  their counts include setup issues.
     */
    private val setupState: (Context, List[reporting.Diagnostic]) =
      setup(setupArgs, rootCtx, sourcesRequired = false) match
        case Some((_, ctx)) =>
          (ctx, rootCtx.reporter.removeBufferedMessages(using ctx))
        case None =>
          val stored = rootCtx.reporter.removeBufferedMessages(using rootCtx)
          val converter = new reporting.CollectingReporter
          stored.foreach(dia => converter.report(dia)(using rootCtx))
          throw new SessionSetupFailedException(
            converter.collectedDiagnostics,
            converter.errorCount,
            converter.warningCount,
          )

    private val setupCtx: Context = setupState._1
    private val setupDiagnostics: List[reporting.Diagnostic] = setupState._2

    private val compiler = newCompiler(using setupCtx)

    def compile(sourcePaths: Array[String], progressCallback: ProgressCallback | Null): Int =
      val reporter = new reporting.ConsoleReporter()
      val compileCtx = freshRunContext(reporter, progressCallback)
      replaySetupDiagnostics(reporter)(using compileCtx)
      val files = sourcePaths.toList.map(compileCtx.getFile)
      val result = doCompile(compiler, files)(using compileCtx)
      if result.hasErrors then 1 else 0

    def compileStructured(
        sourcePaths: Array[String],
        progressCallback: ProgressCallback | Null,
        reporter: reporting.CollectingReporter,
    ): Int =
      val compileCtx = freshRunContext(reporter, progressCallback)
      replaySetupDiagnostics(reporter)(using compileCtx)
      val files = sourcePaths.toList.map(compileCtx.getFile)
      val result = doCompile(compiler, files)(using compileCtx)
      if result.hasErrors then 1 else 0

    def inRun[T](f: Context ?=> T): T =
      val run = compiler.newRun(using freshRunContext(new reporting.ConsoleReporter(), null))
      f(using run.runContext)

    def emitMacroEntryPointsIR(packageNames: Seq[String]): Seq[MacroClassPathScanner.SerializedMacroEntryPointsIR] =
      val emitCtx = freshRunContext(new reporting.ConsoleReporter(), null)
      val run = compiler.newRun(using emitCtx)
      packageNames.toList.map(_.trim).filter(_.nonEmpty).distinct.flatMap { packageName =>
        MacroClassPathScanner.serializedMacroEntryPointsIRInPackage(packageName)(using run.runContext)
      }

    private def replaySetupDiagnostics(reporter: reporting.Reporter)(using Context): Unit =
      setupDiagnostics.foreach(dia => reporter.report(dia))

    private def freshRunContext(reporter: reporting.Reporter, progressCallback: ProgressCallback | Null): Context =
      val fresh = setupCtx.fresh.setReporter(reporter)
      if progressCallback != null then fresh.setProgressCallback(progressCallback)
      fresh

  private def browserCompilerSession(setupArgs: Array[String]): BrowserCompilerSession =
    val key = compilerSessionKey(setupArgs)
    browserCompilerSessions.getOrElseUpdate(key, {
      if browserCompilerSessions.size >= MaxBrowserCompilerSessions then
        browserCompilerSessions.remove(browserCompilerSessions.head._1)
      new BrowserCompilerSession(setupArgs.clone())
    })

  /** Acquire a session for the legacy (console) entry points, preserving their historical
   *  failure behavior: a setup failure surfaces as `js.JavaScriptException`.
   */
  private def legacyBrowserCompilerSession(setupArgs: Array[String]): BrowserCompilerSession =
    try browserCompilerSession(setupArgs)
    catch
      case _: SessionSetupFailedException =>
        throw js.JavaScriptException("failed to configure browser compiler session")

  /** Internal signal that a browser session could not be configured (`setup` returned
   *  `None`). Carries the already-converted setup diagnostics so the structured entry
   *  points can resolve with a result instead of rejecting.
   */
  private final class SessionSetupFailedException(
      val diagnostics: js.Array[js.Dynamic],
      val errorCount: Int,
      val warningCount: Int,
  ) extends RuntimeException("failed to configure browser compiler session")
      with scala.util.control.NoStackTrace

  private def compilerSessionKey(args: Array[String]): String =
    args.map(arg => s"${arg.length}:$arg").mkString

  private final class PhaseTimingProgressCallback(
      recordPhaseTiming: js.Function2[String, Double, Unit],
      compilerRunName: String = "compiler run",
      compilerSetupName: String = "compiler setup",
  ) extends ProgressCallback:
    private var activePhase: String | Null = null
    private var activeStartedAt = 0.0
    private var runStartedAt = Double.NaN
    private var runSetupRecorded = true

    def withCompilerRun[A](operation: => A): A =
      runStartedAt = js.Date.now()
      runSetupRecorded = false
      try operation
      finally
        finishActivePhase()
        if !runSetupRecorded then
          recordDuration(name = compilerSetupName, startedAt = runStartedAt)
        recordDuration(name = compilerRunName, startedAt = runStartedAt)
        runStartedAt = Double.NaN
        runSetupRecorded = true

    override def informUnitStarting(phase: String, unit: CompilationUnit): Unit =
      enterPhase(phase)

    override def progress(current: Int, total: Int, currPhase: String, nextPhase: String): Boolean =
      enterPhase(currPhase)
      true

    def finish(): Unit =
      finishActivePhase()

    private def enterPhase(phase: String): Unit =
      if phase != null && phase.nonEmpty && activePhase != phase then
        if !runSetupRecorded then
          recordDuration(name = compilerSetupName, startedAt = runStartedAt)
          runSetupRecorded = true
        finishActivePhase()
        activePhase = phase
        activeStartedAt = js.Date.now()

    private def finishActivePhase(): Unit =
      activePhase match
        case null => ()
        case phase: String =>
          recordDuration(name = s"phase: $phase", startedAt = activeStartedAt)
          activePhase = null

    private def recordDuration(name: String, startedAt: Double): Unit =
      MainJS.recordDuration(recordPhaseTiming, name, startedAt)

  private def toJSMacroEntryPointsIR(entry: MacroClassPathScanner.SerializedMacroEntryPointsIR): js.Dynamic =
    js.Dynamic.literal(
      packageName = entry.packageName,
      path = entry.path,
      bytes = JSByteArrays.toUint8Array(entry.bytes),
    )

  private def runBrowserSessionWithMacroLinkingImpl(
      setupArgs: Array[String],
      sourcePaths: Array[String],
      relink: js.Function1[js.Dynamic, js.Any],
      remainingRestarts: Int,
      recordPhaseTiming: js.Function2[String, Double, Unit] | Null,
      restartIndex: Int,
  ): Int =
    val code = runBrowserSessionImpl(setupArgs, sourcePaths, phaseTimingProgressCallback(
      recordPhaseTiming,
      compilerRunName = s"compiler run $restartIndex",
      compilerSetupName = s"compiler setup $restartIndex",
    ))
    if code != MissingMacroEntryPointExitCode then code
    else if remainingRestarts <= 0 then
      val missing = lastMissingMacroEntryPointsOrNull
      val ids = if missing == null then "unknown macro entry points" else missing.ids.mkString(", ")
      throw js.JavaScriptException(s"exceeded maximum Scala.js macro relink restarts while resolving $ids")
    else
      val missing = lastMissingMacroEntryPointsOrNull
      if missing == null then
        throw js.JavaScriptException("missing macro entry point interrupt did not record any entry points")

      val emittedEntryPointsIR = recordOptionalTiming(recordPhaseTiming, "macro entrypoint IR"):
        legacyBrowserCompilerSession(setupArgs).emitMacroEntryPointsIR(missing.packageNames)
      val entryPointsIR =
        if emittedEntryPointsIR.nonEmpty then emittedEntryPointsIR
        else missing.entryPointsIR
      val request = js.Dynamic.literal(
        protocolVersion = 1,
        ids = missing.ids.toJSArray,
        packageNames = missing.packageNames.toJSArray,
        entryPointsIR = entryPointsIR.map(toJSMacroEntryPointsIR).toJSArray,
      )
      val linkedCompiler = linkedCompilerFromRelinkResult(awaitMaybePromise(relink(request)))
      recordOptionalTiming(recordPhaseTiming, s"macro compiler restart $restartIndex"):
        restartWithLinkedBrowserSessionCompiler(
          linkedCompiler,
          setupArgs,
          sourcePaths,
          relink,
          remainingRestarts - 1,
          recordPhaseTiming,
          restartIndex,
        )

  /** Structured twin of [[runBrowserSessionWithMacroLinkingImpl]]. Each round buffers its
   *  diagnostics; a round interrupted by the macro relink (internal code 125) discards its
   *  buffer (the relinked restart re-reports everything), while a round that completes
   *  flushes its buffer through `onDiagnostic`. Setup diagnostics replay into every round,
   *  so they survive a discard naturally.
   */
  private def runBrowserSessionWithMacroLinkingStructuredImpl(
      setupArgs: Array[String],
      sourcePaths: Array[String],
      relink: js.Function1[js.Dynamic, js.Any],
      remainingRestarts: Int,
      recordPhaseTiming: js.Function2[String, Double, Unit] | Null,
      onDiagnostic: js.Function1[js.Any, Unit],
      restartIndex: Int,
  ): Int =
    val reporter = new reporting.CollectingReporter
    val code = runBrowserSessionStructuredImpl(
      setupArgs,
      sourcePaths,
      phaseTimingProgressCallback(
        recordPhaseTiming,
        compilerRunName = s"compiler run $restartIndex",
        compilerSetupName = s"compiler setup $restartIndex",
      ),
      reporter,
    )
    if code != MissingMacroEntryPointExitCode then
      flushCollectedDiagnostics(reporter, onDiagnostic)
      code
    else if remainingRestarts <= 0 then
      val missing = lastMissingMacroEntryPointsOrNull
      val ids = if missing == null then "unknown macro entry points" else missing.ids.mkString(", ")
      throw js.JavaScriptException(s"exceeded maximum Scala.js macro relink restarts while resolving $ids")
    else
      val missing = lastMissingMacroEntryPointsOrNull
      if missing == null then
        throw js.JavaScriptException("missing macro entry point interrupt did not record any entry points")

      val emittedEntryPointsIR = recordOptionalTiming(recordPhaseTiming, "macro entrypoint IR"):
        browserCompilerSession(setupArgs).emitMacroEntryPointsIR(missing.packageNames)
      val entryPointsIR =
        if emittedEntryPointsIR.nonEmpty then emittedEntryPointsIR
        else missing.entryPointsIR
      val request = js.Dynamic.literal(
        protocolVersion = 1,
        ids = missing.ids.toJSArray,
        packageNames = missing.packageNames.toJSArray,
        entryPointsIR = entryPointsIR.map(toJSMacroEntryPointsIR).toJSArray,
      )
      val linkedCompiler = linkedCompilerFromRelinkResult(awaitMaybePromise(relink(request)))
      recordOptionalTiming(recordPhaseTiming, s"macro compiler restart $restartIndex"):
        restartWithLinkedBrowserSessionCompilerStructured(
          linkedCompiler,
          setupArgs,
          sourcePaths,
          relink,
          remainingRestarts - 1,
          recordPhaseTiming,
          onDiagnostic,
          restartIndex,
        )

  /** Single-shot structured compile (no macro relinking). A macro interrupt yields exit
   *  code 1 with the interrupted round's buffered diagnostics, or a synthesized error when
   *  that buffer is empty; a setup failure yields a result rather than a rejection.
   */
  private def compileBrowserSessionStructuredImpl(
      setupArgs: Array[String],
      sourcePaths: Array[String],
      recordPhaseTiming: js.Function2[String, Double, Unit] | Null,
  ): js.Dynamic =
    val reporter = new reporting.CollectingReporter
    try
      val code = runBrowserSessionStructuredImpl(
        setupArgs,
        sourcePaths,
        phaseTimingProgressCallback(recordPhaseTiming),
        reporter,
      )
      if code == MissingMacroEntryPointExitCode then interruptedStructuredResult(reporter)
      else structuredResult(code, reporter)
    catch
      case ex: SessionSetupFailedException =>
        sessionSetupFailedResult(ex)

  /** Drive the structured macro-relink session, preferring a retained linked compiler
   *  module and falling back to a fresh local relink loop.
   */
  private def runRetainedMacroSessionStructured(
      setupArgs: Array[String],
      sourcePaths: Array[String],
      recordPhaseTiming: js.Function2[String, Double, Unit] | Null,
      onDiagnostic: js.Function1[js.Any, Unit],
  ): Int =
    retainedMacroCompilerModule() match
      case Some(module) if hasStructuredCompilerEntryPoint(module) =>
        val run = module.selectDynamic(StructuredMacroSessionExport).asInstanceOf[StructuredMacroSessionFn]
        js.await(run(
          setupArgs.toJSArray,
          sourcePaths.toJSArray,
          browserMacroRelink,
          DefaultMaxMacroLinkRestarts,
          orNoopRecord(recordPhaseTiming),
          onDiagnostic,
        ))
      case Some(module) =>
        // Older module without the structured export: diagnostics are lost for this session.
        val run = module.selectDynamic(MacroSessionExport).asInstanceOf[MacroSessionFn]
        js.await(run(
          setupArgs.toJSArray,
          sourcePaths.toJSArray,
          browserMacroRelink,
          DefaultMaxMacroLinkRestarts,
          orNoopRecord(recordPhaseTiming),
        ))
      case None =>
        runBrowserSessionWithMacroLinkingStructuredImpl(
          setupArgs,
          sourcePaths,
          browserMacroRelink,
          DefaultMaxMacroLinkRestarts,
          recordPhaseTiming,
          onDiagnostic,
          restartIndex = 1,
        )

  private def flushCollectedDiagnostics(
      reporter: reporting.CollectingReporter,
      onDiagnostic: js.Function1[js.Any, Unit],
  ): Unit =
    val diagnostics = reporter.collectedDiagnostics
    var i = 0
    while i < diagnostics.length do
      onDiagnostic(diagnostics(i))
      i += 1

  private def structuredResult(exitCode: Int, reporter: reporting.CollectingReporter): js.Dynamic =
    resultLiteral(exitCode, reporter.errorCount, reporter.warningCount, reporter.collectedDiagnostics)

  private def interruptedStructuredResult(reporter: reporting.CollectingReporter): js.Dynamic =
    val diagnostics = reporter.collectedDiagnostics
    if diagnostics.length == 0 then
      val missing = lastMissingMacroEntryPointsOrNull
      val message =
        val raw = if missing == null then null else missing.getMessage
        if raw == null then "Scala.js macro entry point is not linked" else raw
      diagnostics.push(reporting.JSDiagnostics.syntheticErrorDiagnostic(message))
      resultLiteral(exitCode = 1, errorCount = 1, warningCount = 0, diagnostics)
    else
      resultLiteral(exitCode = 1, errorCount = reporter.errorCount, warningCount = reporter.warningCount, diagnostics)

  private def sessionSetupFailedResult(ex: SessionSetupFailedException): js.Dynamic =
    val exitCode = if ex.errorCount > 0 then 1 else 0
    resultLiteral(exitCode, ex.errorCount, ex.warningCount, ex.diagnostics)

  private def buildStructuredResultFromCollected(exitCode: Int, collected: js.Array[js.Dynamic]): js.Dynamic =
    var errorCount = 0
    var warningCount = 0
    var i = 0
    while i < collected.length do
      collected(i).selectDynamic("severity").asInstanceOf[String] match
        case "error"   => errorCount += 1
        case "warning" => warningCount += 1
        case _         => ()
      i += 1
    resultLiteral(exitCode, errorCount, warningCount, collected)

  private def resultLiteral(
      exitCode: Int,
      errorCount: Int,
      warningCount: Int,
      diagnostics: js.Array[js.Dynamic],
  ): js.Dynamic =
    js.Dynamic.literal(
      exitCode = exitCode,
      hasErrors = exitCode == 1,
      errorCount = errorCount,
      warningCount = warningCount,
      diagnostics = diagnostics,
    )

  private def phaseTimingProgressCallback(
      recordPhaseTiming: js.Function2[String, Double, Unit] | Null,
      compilerRunName: String = "compiler run",
      compilerSetupName: String = "compiler setup",
  ): ProgressCallback | Null =
    if recordPhaseTiming == null then null
    else new PhaseTimingProgressCallback(
      recordPhaseTiming.asInstanceOf[js.Function2[String, Double, Unit]],
      compilerRunName,
      compilerSetupName,
    )

  private def recordOptionalTiming[A](recordTiming: js.Function2[String, Double, Unit] | Null, name: String)(operation: => A): A =
    if recordTiming == null then operation
    else
      val startedAt = js.Date.now()
      try operation
      finally recordDuration(recordTiming.asInstanceOf[js.Function2[String, Double, Unit]], name, startedAt)

  private def recordDuration(recordTiming: js.Function2[String, Double, Unit], name: String, startedAt: Double): Unit =
    val durationMs = math.max(0.0, js.Date.now() - startedAt)
    if durationMs.isFinite then recordTiming(name, durationMs)

  private def registerMacroEntryPoints(entries: js.Array[js.Array[js.Any]]): Unit =
    entries.foreach { entry =>
      val id = entry(0).asInstanceOf[String]
      val f = entry(1).asInstanceOf[js.Function1[js.Array[js.Any], js.Any]]
      MacroRuntimeRegistry.register(id, args => f(args.map(_.asInstanceOf[js.Any]).toJSArray))
    }

  private def awaitMaybePromise(value: js.Any): js.Any =
    if value == null || js.isUndefined(value) then
      throw js.JavaScriptException("Scala.js macro relinker did not return a compiler module")

    val dynamicValue = value.asInstanceOf[js.Dynamic]
    val thenValue = dynamicValue.selectDynamic("then")
    if js.typeOf(thenValue) == "function" then
      js.await(value.asInstanceOf[js.Promise[js.Any]])
    else value

  private def linkedCompilerFromRelinkResult(value: js.Any): js.Dynamic =
    if value == null || js.isUndefined(value) then
      throw js.JavaScriptException("Scala.js macro relinker did not return a compiler module")
    else if js.typeOf(value) == "string" then
      importPublicModule(value.toString)
    else
      val module = value.asInstanceOf[js.Dynamic]
      if hasCompilerEntryPoint(module) then module
      else
        val moduleUrl = module.selectDynamic("moduleUrl")
        if js.typeOf(moduleUrl) == "string" then importPublicModule(moduleUrl.toString)
        else module

  private def importPublicModule(url: String): js.Dynamic =
    rememberPublicModuleUrl(url)
    importModule(url)

  private def hasCompilerEntryPoint(module: js.Dynamic): Boolean =
    hasMacroSessionEntryPoint(module) || hasStructuredCompilerEntryPoint(module)

  private def hasMacroSessionEntryPoint(module: js.Dynamic): Boolean =
    js.typeOf(module.selectDynamic(MacroSessionExport)) == "function"

  private def hasStructuredCompilerEntryPoint(module: js.Dynamic): Boolean =
    js.typeOf(module.selectDynamic(StructuredMacroSessionExport)) == "function"

  private def restartWithLinkedBrowserSessionCompiler(
      linkedCompiler: js.Dynamic,
      setupArgs: Array[String],
      sourcePaths: Array[String],
      relink: js.Function1[js.Dynamic, js.Any],
      remainingRestarts: Int,
      recordPhaseTiming: js.Function2[String, Double, Unit] | Null,
      restartIndex: Int,
  ): Int =
    val linkedRecordPhaseTiming = linkedCompilerTimingRecorder(recordPhaseTiming, restartIndex + 1)
    val runValue = linkedCompiler.selectDynamic(MacroSessionExport)
    if js.typeOf(runValue) != "function" then
      throw js.JavaScriptException(s"Scala.js macro relinker returned a module without $MacroSessionExport")
    val record =
      if linkedRecordPhaseTiming != null then linkedRecordPhaseTiming.asInstanceOf[js.Function2[String, Double, Unit]]
      else ((_: String, _: Double) => ()).asInstanceOf[js.Function2[String, Double, Unit]]
    val run = runValue.asInstanceOf[MacroSessionFn]
    js.await(run(setupArgs.toJSArray, sourcePaths.toJSArray, relink, remainingRestarts, record))

  /** Structured twin of [[restartWithLinkedBrowserSessionCompiler]]. Prefers the linked
   *  module's structured export, forwarding `onDiagnostic`; if the module only exposes the
   *  legacy export (an older module), falls back to it and loses structured diagnostics for
   *  that restart.
   */
  private def restartWithLinkedBrowserSessionCompilerStructured(
      linkedCompiler: js.Dynamic,
      setupArgs: Array[String],
      sourcePaths: Array[String],
      relink: js.Function1[js.Dynamic, js.Any],
      remainingRestarts: Int,
      recordPhaseTiming: js.Function2[String, Double, Unit] | Null,
      onDiagnostic: js.Function1[js.Any, Unit],
      restartIndex: Int,
  ): Int =
    val record = orNoopRecord(linkedCompilerTimingRecorder(recordPhaseTiming, restartIndex + 1))
    if hasStructuredCompilerEntryPoint(linkedCompiler) then
      val run = linkedCompiler.selectDynamic(StructuredMacroSessionExport).asInstanceOf[StructuredMacroSessionFn]
      js.await(run(setupArgs.toJSArray, sourcePaths.toJSArray, relink, remainingRestarts, record, onDiagnostic))
    else
      val runValue = linkedCompiler.selectDynamic(MacroSessionExport)
      if js.typeOf(runValue) != "function" then
        throw js.JavaScriptException(s"Scala.js macro relinker returned a module without $MacroSessionExport")
      val run = runValue.asInstanceOf[MacroSessionFn]
      js.await(run(setupArgs.toJSArray, sourcePaths.toJSArray, relink, remainingRestarts, record))

  private def orNoopRecord(
      record: js.Function2[String, Double, Unit] | Null,
  ): js.Function2[String, Double, Unit] =
    if record != null then record.asInstanceOf[js.Function2[String, Double, Unit]]
    else ((_: String, _: Double) => ()).asInstanceOf[js.Function2[String, Double, Unit]]

  private def linkedCompilerTimingRecorder(
      recordPhaseTiming: js.Function2[String, Double, Unit] | Null,
      compilerRunIndex: Int,
  ): js.Function2[String, Double, Unit] | Null =
    if recordPhaseTiming == null then null
    else
      val record = recordPhaseTiming.asInstanceOf[js.Function2[String, Double, Unit]]
      (name: String, durationMs: Double) =>
        val adjustedName =
          if name == "compiler run" then s"compiler run $compilerRunIndex"
          else if name == "compiler setup" then s"compiler setup $compilerRunIndex"
          else name
        record(adjustedName, durationMs)

  private def logThrowable(t: Throwable): Unit =
    js.Dynamic.global.console.error(
      "scala3-compiler-sjs throwable",
      t.getClass.getName.asInstanceOf[js.Any],
      Option(t.getMessage).orNull.asInstanceOf[js.Any],
      t.toString.asInstanceOf[js.Any]
    )

  private def logJavaScriptException(jse: js.JavaScriptException): Unit =
    val raw = jse.exception
    val rawDyn = raw.asInstanceOf[js.Dynamic]
    val ctorName =
      try
        val ctor = rawDyn.selectDynamic("constructor")
        if js.isUndefined(ctor) || ctor == null then null
        else
          val name = ctor.selectDynamic("name")
          if js.isUndefined(name) || name == null then null else name.toString
      catch
        case NonFatal(_) => null
    val rawStack =
      try
        val stack = rawDyn.selectDynamic("stack")
        if js.isUndefined(stack) || stack == null then null else stack.toString
      catch
        case NonFatal(_) => null

    js.Dynamic.global.console.error(
      "scala3-compiler-sjs js exception",
      ctorName.asInstanceOf[js.Any],
      raw.asInstanceOf[js.Any],
      rawStack.asInstanceOf[js.Any]
    )

  private def resolveLinkedMacroEntryPoint(id: String): Boolean =
    val packageName = MacroRuntimeExports.packageNameFromMacroId(id)

    try
      val exportName = MacroRuntimeExports.entryPointsExportName(packageName)
      currentModules().exists { module =>
        val exported = module.selectDynamic(exportName)
        if js.typeOf(exported) == "function" then
          val exportedFunction =
            exported.asInstanceOf[js.Function0[js.Array[js.Array[js.Any]]]]
          val entries = exportedFunction()
          registerMacroEntryPoints(entries)
          MacroRuntimeRegistry.hasRegistered(id)
        else false
      }
    catch
      case NonFatal(_) => false

  private def currentModules(): List[js.Dynamic] =
    currentModuleUrls().flatMap(importModuleIfAvailable)

  private def retainedMacroCompilerModule(): Option[js.Dynamic] =
    rememberedPublicModuleUrls().reverseIterator
      .flatMap(url => importModuleIfAvailable(url))
      .find(hasCompilerEntryPoint)

  private def importModule(url: String): js.Dynamic =
    importedModuleNamespaces.getOrElseUpdate(url, js.await(js.`import`[js.Dynamic](url)))

  private def importModuleIfAvailable(url: String): Option[js.Dynamic] =
    try Some(importModule(url))
    catch
      case NonFatal(_) => None

  private def currentModuleUrls(): List[String] =
    val metaUrl = js.`import`.meta.selectDynamic("url").toString
    val publicModuleUrl =
      if metaUrl.endsWith("/__loader.js") then Some(metaUrl.stripSuffix("__loader.js") + "main.js")
      else None
    (rememberedPublicModuleUrls().reverse ::: metaUrl :: publicModuleUrl.toList).distinct

  private def rememberedPublicModuleUrls(): List[String] =
    val urls = GlobalThis.asInstanceOf[js.Dynamic].selectDynamic(PublicModuleUrlsGlobalName)
    if urls == null || js.isUndefined(urls) then Nil
    else urls.asInstanceOf[js.Array[String]].toSeq.toList

  private def rememberPublicModuleUrl(url: String): Unit =
    val urls = publicModuleUrls()
    var i = 0
    while i < urls.length do
      if urls(i) == url then return
      i += 1
    urls.push(url)
    ()

  private def publicModuleUrls(): js.Array[String] =
    val globalThis = GlobalThis.asInstanceOf[js.Dynamic]
    val urls = globalThis.selectDynamic(PublicModuleUrlsGlobalName)
    if urls == null || js.isUndefined(urls) then
      val created = new js.Array[String]()
      globalThis.updateDynamic(PublicModuleUrlsGlobalName)(created)
      created
    else urls.asInstanceOf[js.Array[String]]

  private def clearPublicModuleUrls(): Unit =
    val globalThis = GlobalThis.asInstanceOf[js.Dynamic]
    globalThis.updateDynamic(PublicModuleUrlsGlobalName)(new js.Array[String]())
