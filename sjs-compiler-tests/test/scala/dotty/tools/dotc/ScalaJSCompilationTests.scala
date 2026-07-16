package dotty
package tools
package dotc

import java.io.{BufferedReader, BufferedWriter, File, InputStreamReader, OutputStreamWriter}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

import org.junit.{ Test, BeforeClass, AfterClass }
import org.junit.experimental.categories.Category

import scala.collection.mutable
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._
import reporting.TestReporter
import vulpix._
import org.junit.Ignore

@Category(Array(classOf[ScalaJSCompilationTests]))
class ScalaJSCompilationTests {
  import ParallelTesting._
  import TestConfiguration._
  import ScalaJSCompilationTests._
  import CompilationTest.aggregateTests

  // Negative tests ------------------------------------------------------------

  @Test def negScalaJS: Unit = {
    implicit val testGroup: TestGroup = TestGroup("negScalaJS")
    aggregateTests(
      compileFilesInDir("tests/neg-scalajs", scalaJSOptions),
    ).checkExpectedErrors()
  }

  @Test def runScalaJS: Unit = {
    implicit val testGroup: TestGroup = TestGroup("runScalaJS")
    aggregateTests(
      compileFilesInDir("tests/run", scalaJSOptions),
    ).checkRuns()
  }

  // Structured entry point tests ----------------------------------------------
  // These exercise `compileScala3SjsAsync(setupArgs, sourcePaths)` through the
  // structured request variant of the compiler-server protocol (see
  // SjsCompilerBridge). Each no-ops gracefully when the bridge is disabled.

  /** A single known type error at a known position must surface as exactly one
   *  structured error diagnostic with precise, 0-based coordinates and metadata.
   */
  @Test def structuredCompileReportsErrorDiagnostics: Unit =
    if SjsCompilerBridge.isEnabled then
      // The `val x` line stays at 0-based line 1, and `"s"` at 0-based column 15,
      // whether the class body uses braces or significant indentation.
      withTempSource("class A {\n  val x: Int = \"s\"\n}\n") { (path, fileName) =>
        val result = structuredCompile(Array(path), Nil)
        assert(result.exitCode == 1, s"exitCode: expected 1, got ${result.exitCode}")
        assert(result.hasErrors, "hasErrors: expected true")
        assert(result.errorCount == 1, s"errorCount: expected 1, got ${result.errorCount}")
        val errors = result.diagnostics.filter(_.severity == "error")
        assert(errors.sizeIs == 1, s"expected exactly one error diagnostic, got ${errors.map(_.message)}")
        val diag = errors.head
        assert(diag.file.exists(_.endsWith(fileName)), s"file: expected to end with $fileName, got ${diag.file}")
        assert(diag.line.contains(1), s"line: expected 1 (0-based), got ${diag.line}")
        assert(diag.column.contains(15), s"column: expected 15 (0-based), got ${diag.column}")
        assert(diag.code.exists(_.matches("E\\d{3}")), s"code: expected to match E\\d{3}, got ${diag.code}")
        assert(diag.name.isDefined, s"name: expected non-null, got ${diag.name}")
        assert(diag.message.contains("Found") && diag.message.contains("Required"),
          s"message: expected type-mismatch text, got ${diag.message}")
        assert(diag.rendered.contains("val x: Int = \"s\""),
          s"rendered: expected to contain the source line, got ${diag.rendered}")
        assert(!result.diagnostics.exists(_.message.matches("(?s).*\\d+ errors? found.*")),
          s"no diagnostic should be a compiler summary line, got ${result.diagnostics.map(_.message)}")
      }

  /** Flags must flow through to the compiler: the same deprecated-member source
   *  behaves differently under no flags, `-deprecation`, and `-deprecation -Werror`.
   */
  @Test def structuredCompileHonorsFlags: Unit =
    if SjsCompilerBridge.isEnabled then
      val content =
        "object Dep {\n" +
        "  @deprecated(\"old\", \"1.0\") def oldApi: Int = 1\n" +
        "  def useIt: Int = oldApi\n" +
        "}\n"
      withTempSource(content) { (path, fileName) =>
        val sources = Array(path)
        val useSiteLine = 2 // 0-based line of `def useIt: Int = oldApi`

        // (a) No extra flags: no positioned use-site warning (a summary advisory is allowed).
        val plain = structuredCompile(sources, Nil)
        assert(plain.exitCode == 0, s"(a) exitCode: expected 0, got ${plain.exitCode}")
        assert(!plain.diagnostics.exists(d => d.severity == "warning" && d.line.contains(useSiteLine)),
          "(a) expected no warning positioned at the use site, got " +
            plain.diagnostics.filter(_.severity == "warning").map(d => d.line -> d.message).toString)

        // (b) -deprecation: exactly one positioned use-site deprecation warning.
        val deprecated = structuredCompile(sources, Seq("-deprecation"))
        assert(deprecated.exitCode == 0, s"(b) exitCode: expected 0, got ${deprecated.exitCode}")
        val positioned =
          deprecated.diagnostics.filter(d => d.severity == "warning" && d.line.contains(useSiteLine))
        assert(positioned.sizeIs == 1,
          "(b) expected exactly one warning at the use site, got " +
            deprecated.diagnostics.map(d => (d.severity, d.line, d.message)).toString)
        assert(positioned.head.message.toLowerCase.contains("deprecat"),
          s"(b) warning message should mention deprecation, got ${positioned.head.message}")
        assert(positioned.head.file.exists(_.endsWith(fileName)),
          s"(b) warning file: expected to end with $fileName, got ${positioned.head.file}")

        // (c) -deprecation -Werror: the warning becomes fatal.
        val werror = structuredCompile(sources, Seq("-deprecation", "-Werror"))
        assert(werror.exitCode == 1, s"(c) exitCode: expected 1, got ${werror.exitCode}")
      }

  /** An unknown setup flag never aborts the compile: it resolves cleanly
   *  (exitCode 0, no errors) but is surfaced as a warning naming the flag, rather
   *  than being silently swallowed. The otherwise-valid source still compiles to
   *  exitCode 0, proving setup continued past the bad flag into a real compile
   *  (faithful to scalac/JVM-embedder semantics — strictness is opt-in via -Werror).
   */
  @Test def structuredCompileSurfacesUnknownFlag: Unit =
    if SjsCompilerBridge.isEnabled then
      withTempSource("class Ok {\n  def value: Int = 1\n}\n") { (path, _) =>
        val badFlag = "-definitely-not-a-real-flag"
        val result = structuredCompile(Array(path), Seq(badFlag))
        assert(result.exitCode == 0, s"exitCode: expected 0 (bad flag must not abort), got ${result.exitCode}")
        assert(!result.hasErrors, "hasErrors: expected false")
        assert(result.warningCount >= 1, s"warningCount: expected >= 1, got ${result.warningCount}")
        assert(result.diagnostics.exists(d => d.severity == "warning" && d.message.contains(badFlag)),
          "expected a warning diagnostic mentioning the bad flag, got " +
            result.diagnostics.map(d => (d.severity, d.message)).toString)
      }

  /** A clean source compiles quietly: zero errors, zero warnings, no diagnostics. */
  @Test def structuredCompileCleanSourceIsQuiet: Unit =
    if SjsCompilerBridge.isEnabled then
      withTempSource("class Clean {\n  def value: Int = 42\n}\n") { (path, _) =>
        val result = structuredCompile(Array(path), Nil)
        assert(result.exitCode == 0, s"exitCode: expected 0, got ${result.exitCode}")
        assert(!result.hasErrors, "hasErrors: expected false")
        assert(result.errorCount == 0, s"errorCount: expected 0, got ${result.errorCount}")
        assert(result.diagnostics.isEmpty,
          s"expected no diagnostics, got ${result.diagnostics.map(d => (d.severity, d.message))}")
      }

  /** Write `content` to a fresh temp `.scala` file, run `body` with its absolute
   *  path and its file name, then delete it.
   */
  private def withTempSource(content: String)(body: (String, String) => Unit): Unit =
    val source = Files.createTempFile("sjsStructured", ".scala")
    Files.writeString(source, content, StandardCharsets.UTF_8)
    try body(source.toAbsolutePath.toString, source.getFileName.toString)
    finally Files.deleteIfExists(source)

  /** Compile `sources` through the structured entry point with a base classpath
   *  matching the neg/run tests (plus the bridge's rt.jar), color disabled, and a
   *  throwaway output directory. `extraFlags` are appended to the setup args.
   */
  private def structuredCompile(sources: Array[String], extraFlags: Seq[String]): SjsCompilerBridge.StructuredResult =
    val outDir = Files.createTempDirectory("sjsStructuredOut")
    try
      val setupArgs = Array(
        "-color:never",
        "-d", outDir.toAbsolutePath.toString,
        "-classpath", scalaJSOptions.defaultClassPath,
      ) ++ extraFlags
      SjsCompilerBridge.processStructured(setupArgs, sources).get
    finally
      deleteRecursively(outDir)

  private def deleteRecursively(path: Path): Unit =
    if Files.isDirectory(path) then
      val entries = Files.list(path)
      try entries.forEach(child => deleteRecursively(child))
      finally entries.close()
    Files.deleteIfExists(path)
}

object ScalaJSCompilationTests extends ParallelTesting {
  implicit val summaryReport: SummaryReporting = new SummaryReport

  // Test suite configuration --------------------------------------------------
  def maxDuration = 60.seconds
  def numberOfWorkers = 5
  def safeMode = Properties.testsSafeMode
  def isInteractive = SummaryReport.isInteractive
  def testFilter = Properties.testsFilter
  def updateCheckFiles: Boolean = Properties.testsUpdateCheckfile
  def failedTests = TestReporter.lastRunFailedTests

  @AfterClass def tearDown(): Unit =
    SjsCompilerBridge.shutdown()
    cleanup()
    val skipped = skippedSeparateCompilationGroups.size
    if skipped > 0 then
      println(
        s"Skipped $skipped separate-compilation test groups: " +
        "JS backend emits only .sjsir; no .tasty/.class for later rounds")
    val skippedMacros = skippedSameRunMacroTests.size
    if skippedMacros > 0 then
      println(
        s"Skipped $skippedMacros same-run macro test(s): a macro defined and " +
        "used in one compilation round needs a Scala.js linker to relink the " +
        "freshly-emitted .sjsir on the fly, and the node batch harness has none " +
        "(only the browser session does). Covered by the browser validation suite.\n" +
        skippedSameRunMacroTests.asScala.toList.sorted.map("  " + _).mkString("\n"))
    summaryReport.echoSummary()

  // Run tests -----------------------------------------------------------------

  /** Test dirs skipped because they need more than one compilation round.
   *  Deduplicated by title; reported once at teardown.
   */
  private val skippedSeparateCompilationGroups =
    java.util.concurrent.ConcurrentHashMap.newKeySet[String]()

  /** Test dirs skipped because they define and use a macro in a single
   *  compilation round (see [[processScalaSources]]). Deduplicated; reported
   *  once at teardown.
   */
  private val skippedSameRunMacroTests =
    java.util.concurrent.ConcurrentHashMap.newKeySet[String]()

  /** The Scala.js-hosted compiler runs only GenSJSIR and never writes .tasty/.class
   *  (those are produced by GenBCode, which is absent from the JS `PlatformBackendPhases`).
   *  A later compilation round therefore cannot read the symbols an earlier round
   *  produced, so any multi-round separate-compilation test fails with `Not Found`.
   *  Detect these structurally (more than one compilation group) rather than by a
   *  hardcoded list, so newly-added upstream tests are skipped automatically.
   */
  private def isMultiRoundSeparateCompilation(testSource: TestSource): Boolean =
    testSource match
      case source: SeparateCompilationSource if source.compilationGroups.sizeIs > 1 => true
      case _ => false

  override protected def shouldSkipTestSource(testSource: TestSource): Boolean =
    if isMultiRoundSeparateCompilation(testSource) then
      skippedSeparateCompilationGroups.add(testSource.title)
      true
    else
      testSource.allToolArgs.get(ToolName.ScalaJS).exists(_.contains("--skip"))
      || super.shouldSkipTestSource(testSource)

  override protected def testPlatform: TestPlatform = TestPlatform.ScalaJS

  override protected def processScalaSources(
      args: Array[String],
      sourcePaths: Array[String],
      targetDir: File,
      reporter: TestReporter,
      driver: Driver,
  ): Option[ScalaCompilerFailure] =
    if SjsCompilerBridge.isEnabled then
      SjsCompilerBridge.process(args ++ sourcePaths) match
        case Some(result) if result.missingMacroEntryPoint =>
          // A macro defined and used in the same compilation round can only be
          // expanded by linking the macro's freshly-emitted .sjsir on the fly.
          // That linker exists only in the browser session path
          // (BrowserMacroLinkerRuntime installs globalThis.ScalaJSLinker); the
          // node batch entry point (runScala3CompilerSJSAsync) has none, so the
          // entry point is never registered and the compiler exits with the
          // missing-macro-entry-point interrupt. Skip such tests rather than
          // fail them, mirroring the multi-round separate-compilation skip.
          // Same-run macros ARE exercised by the browser validation suite.
          // Detection is scoped to that exact outcome (interrupt exit code plus
          // the specific diagnostic), so a generic compile failure still fails.
          // The compile step is shared with neg/pos runs; in the unlikely event
          // this outcome ever arose there it becomes a skip (never a pass).
          skippedSameRunMacroTests.add(sameRunMacroSkipLabel(sourcePaths))
          reporter.setSkip()
          None
        case Some(result) if result.failed =>
          Some(ScalaCompilerFailure(
            result.output,
            "scala3-compiler-sjs",
            Some(SjsCompilerBridge.checkOutputLines(result.output)),
          ))
        case _ =>
          None
    else super.processScalaSources(args, sourcePaths, targetDir, reporter, driver)

  /** A short, stable label (e.g. `run/25333`) for a skipped same-run macro
   *  test, derived from the directory holding its sources.
   */
  private def sameRunMacroSkipLabel(sourcePaths: Array[String]): String =
    sourcePaths.headOption match
      case Some(path) =>
        val dir = new File(path).getParentFile
        if dir == null then "<unknown>"
        else Option(dir.getParentFile).fold(dir.getName)(parent => s"${parent.getName}/${dir.getName}")
      case None => "<unknown>"

  override def runMain(classPath: String, toolArgs: ToolArgs)(implicit summaryReport: SummaryReporting): Status =
    import scala.concurrent.ExecutionContext.Implicits.global

    val scalaJSOptions = toolArgs.getOrElse(ToolName.ScalaJS, Nil)

    try
      val useCompliantSemantics = scalaJSOptions.contains("--compliant-semantics")
      val sjsCode = ScalaJSLink.link(classPath, useCompliantSemantics)
      JSRun.runJSCode(sjsCode)
    catch
      case t: Exception =>
        val writer = new java.io.StringWriter()
        t.printStackTrace(new java.io.PrintWriter(writer))
        Failure(writer.toString())
  end runMain
}

private object SjsCompilerBridge:
  private val CompilerMainProperty = "dotty.tests.sjsCompilerMain"
  private val ExtraClasspathProperty = "dotty.tests.sjsCompilerExtraClasspath"
  private val NodeFlagsProperty = "dotty.tests.sjsCompilerNodeFlags"
  private val CompilerName = "scala3-compiler-sjs"
  private val Response = """\{"id":(\d+),"code":(-?\d+),"output":"([^"]*)"\}""".r

  /** Exit code the Scala.js-hosted compiler returns when a macro entry point
   *  could not be linked; see `MainJS.MissingMacroEntryPointExitCode`.
   */
  private val MissingMacroEntryPointExitCode = 125
  /** Substring of the diagnostic emitted for that outcome
   *  ("... entry point is not linked" / "... entry points are not linked").
   */
  private val MissingMacroEntryPointMarker = "Scala.js macro entry point"

  private val servers = mutable.LinkedHashSet.empty[CompilerServer]
  private val threadServer = ThreadLocal[CompilerServer]()

  /** Outcome of one compiler invocation against the Scala.js-hosted compiler. */
  final case class CompileResult(code: Int, output: String):
    /** True when the compile ended with a nonzero exit code. */
    def failed: Boolean = code != 0
    /** True for the specific "same-run macro could not be linked" outcome:
     *  the interrupt exit code together with its diagnostic. Keyed on both so a
     *  generic compile failure is never mistaken for it.
     */
    def missingMacroEntryPoint: Boolean =
      code == MissingMacroEntryPointExitCode && output.contains(MissingMacroEntryPointMarker)

  def isEnabled: Boolean =
    sys.props.contains(CompilerMainProperty)

  def process(args: Array[String]): Option[CompileResult] =
    sys.props.get(CompilerMainProperty).map { compilerMain =>
      val (code, output) = server(compilerMain).run(withExtraClasspath(args))
      CompileResult(code, output)
    }

  def checkOutputLines(output: String): List[String] =
    output.linesIterator.filterNot { line =>
      line.startsWith("checking ") || line.matches("\\d+ (?:warning|error)s? found")
    }.toList

  // Structured compile support ------------------------------------------------
  // Mirrors the BINDING CONTRACT for `compileScala3SjsAsync`. The server sends
  // the structured result as base64url-encoded `JSON.stringify(result)` in the
  // existing `output` slot (with `code` = exitCode), so the legacy `Response`
  // regex keeps matching; we decode and parse it back into these case classes.

  /** One diagnostic from a structured compile. Lines/columns/offsets are 0-based
   *  and may be absent (contract `null`); `code`/`name`/`file` may also be absent.
   */
  final case class Diagnostic(
      severity: String,
      message: String,
      rendered: String,
      file: Option[String],
      line: Option[Int],
      column: Option[Int],
      endLine: Option[Int],
      endColumn: Option[Int],
      offset: Option[Int],
      endOffset: Option[Int],
      code: Option[String],
      name: Option[String],
  )

  /** The full structured result of a single `compileScala3SjsAsync` call. */
  final case class StructuredResult(
      exitCode: Int,
      hasErrors: Boolean,
      errorCount: Int,
      warningCount: Int,
      diagnostics: List[Diagnostic],
  )

  /** Run a structured compile. `setupArgs` must not contain source paths; the
   *  bridge's rt.jar is appended to the `-classpath` entry exactly as for legacy
   *  compilations. Returns `None` only when the bridge is disabled.
   */
  def processStructured(setupArgs: Array[String], sources: Array[String]): Option[StructuredResult] =
    sys.props.get(CompilerMainProperty).map { compilerMain =>
      val (code, json) = server(compilerMain).runStructured(withExtraClasspath(setupArgs), sources)
      if code < 0 then
        // Reserved for infrastructure failures (a rejected promise); the contract
        // pins exitCode to 0|1, so a negative code never carries a real result.
        throw IllegalStateException(s"$CompilerName structured compile failed to run: $json")
      decodeStructuredResult(json)
    }

  private def decodeStructuredResult(json: String): StructuredResult =
    Json.parse(json) match
      case Json.Obj(fields) =>
        StructuredResult(
          exitCode = intField(fields, "exitCode"),
          hasErrors = boolField(fields, "hasErrors"),
          errorCount = intField(fields, "errorCount"),
          warningCount = intField(fields, "warningCount"),
          diagnostics = decodeDiagnostics(fields),
        )
      case other => throw IllegalStateException(s"Expected a JSON object, got: $other")

  private def decodeDiagnostics(fields: Map[String, Json.Value]): List[Diagnostic] =
    fields.get("diagnostics") match
      case Some(Json.Arr(values)) => values.map(decodeDiagnostic)
      case other => throw IllegalStateException(s"Expected a 'diagnostics' array, got: $other")

  private def decodeDiagnostic(value: Json.Value): Diagnostic =
    value match
      case Json.Obj(fields) =>
        Diagnostic(
          severity = stringField(fields, "severity"),
          message = stringField(fields, "message"),
          rendered = stringField(fields, "rendered"),
          file = optStringField(fields, "file"),
          line = optIntField(fields, "line"),
          column = optIntField(fields, "column"),
          endLine = optIntField(fields, "endLine"),
          endColumn = optIntField(fields, "endColumn"),
          offset = optIntField(fields, "offset"),
          endOffset = optIntField(fields, "endOffset"),
          code = optStringField(fields, "code"),
          name = optStringField(fields, "name"),
        )
      case other => throw IllegalStateException(s"Expected a diagnostic object, got: $other")

  private def intField(fields: Map[String, Json.Value], key: String): Int =
    fields.get(key) match
      case Some(Json.Num(value)) => value.toInt
      case other => throw IllegalStateException(s"Expected a number for '$key', got: $other")

  private def boolField(fields: Map[String, Json.Value], key: String): Boolean =
    fields.get(key) match
      case Some(Json.Bool(value)) => value
      case other => throw IllegalStateException(s"Expected a boolean for '$key', got: $other")

  private def stringField(fields: Map[String, Json.Value], key: String): String =
    fields.get(key) match
      case Some(Json.Str(value)) => value
      case other => throw IllegalStateException(s"Expected a string for '$key', got: $other")

  private def optStringField(fields: Map[String, Json.Value], key: String): Option[String] =
    fields.get(key) match
      case Some(Json.Str(value)) => Some(value)
      case Some(Json.JsNull) | None => None
      case other => throw IllegalStateException(s"Expected a string or null for '$key', got: $other")

  private def optIntField(fields: Map[String, Json.Value], key: String): Option[Int] =
    fields.get(key) match
      case Some(Json.Num(value)) => Some(value.toInt)
      case Some(Json.JsNull) | None => None
      case other => throw IllegalStateException(s"Expected a number or null for '$key', got: $other")

  /** A small, self-contained JSON reader for the server's `JSON.stringify`
   *  output. Private to the bridge; it covers the full value grammar (objects,
   *  arrays, strings with standard escapes including `\\uXXXX`, numbers,
   *  booleans, null) so it decodes the flat result shape losslessly.
   */
  private object Json:
    sealed trait Value
    final case class Str(value: String) extends Value
    final case class Num(value: Double) extends Value
    final case class Bool(value: Boolean) extends Value
    case object JsNull extends Value
    final case class Arr(values: List[Value]) extends Value
    final case class Obj(fields: Map[String, Value]) extends Value

    def parse(input: String): Value =
      val parser = Parser(input)
      parser.skipWhitespace()
      val value = parser.parseValue()
      parser.skipWhitespace()
      if !parser.atEnd then parser.fail("unexpected trailing content")
      value

    private final class Parser(source: String):
      private var pos = 0

      def atEnd: Boolean = pos >= source.length

      def fail(message: String): Nothing =
        throw IllegalStateException(s"Invalid JSON at position $pos: $message")

      def skipWhitespace(): Unit =
        while pos < source.length && isWhitespace(source.charAt(pos)) do pos += 1

      private def isWhitespace(c: Char): Boolean =
        c == ' ' || c == '\t' || c == '\n' || c == '\r'

      def parseValue(): Value =
        skipWhitespace()
        if atEnd then fail("unexpected end of input")
        source.charAt(pos) match
          case '{' => parseObject()
          case '[' => parseArray()
          case '"' => Str(parseString())
          case 't' => parseLiteral("true", Bool(true))
          case 'f' => parseLiteral("false", Bool(false))
          case 'n' => parseLiteral("null", JsNull)
          case c if c == '-' || (c >= '0' && c <= '9') => parseNumber()
          case c => fail(s"unexpected character '$c'")

      private def parseLiteral(literal: String, value: Value): Value =
        if source.regionMatches(pos, literal, 0, literal.length) then
          pos += literal.length
          value
        else fail(s"expected '$literal'")

      private def parseObject(): Value =
        pos += 1 // consume '{'
        skipWhitespace()
        if !atEnd && source.charAt(pos) == '}' then
          pos += 1
          Obj(Map.empty)
        else
          val fields = Map.newBuilder[String, Value]
          var open = true
          while open do
            skipWhitespace()
            if atEnd || source.charAt(pos) != '"' then fail("expected an object key")
            val key = parseString()
            skipWhitespace()
            if atEnd || source.charAt(pos) != ':' then fail("expected ':'")
            pos += 1 // consume ':'
            fields += (key -> parseValue())
            skipWhitespace()
            if atEnd then fail("unterminated object")
            source.charAt(pos) match
              case ',' => pos += 1
              case '}' => pos += 1; open = false
              case c => fail(s"unexpected character '$c' in object")
          Obj(fields.result())

      private def parseArray(): Value =
        pos += 1 // consume '['
        skipWhitespace()
        if !atEnd && source.charAt(pos) == ']' then
          pos += 1
          Arr(Nil)
        else
          val values = List.newBuilder[Value]
          var open = true
          while open do
            values += parseValue()
            skipWhitespace()
            if atEnd then fail("unterminated array")
            source.charAt(pos) match
              case ',' => pos += 1
              case ']' => pos += 1; open = false
              case c => fail(s"unexpected character '$c' in array")
          Arr(values.result())

      private def parseString(): String =
        pos += 1 // consume opening quote
        val builder = StringBuilder()
        var done = false
        while !done do
          if atEnd then fail("unterminated string")
          val c = source.charAt(pos)
          pos += 1
          c match
            case '"' => done = true
            case '\\' =>
              if atEnd then fail("unterminated escape")
              val escaped = source.charAt(pos)
              pos += 1
              escaped match
                case '"' => builder.append('"')
                case '\\' => builder.append('\\')
                case '/' => builder.append('/')
                case 'b' => builder.append('\b')
                case 'f' => builder.append('\f')
                case 'n' => builder.append('\n')
                case 'r' => builder.append('\r')
                case 't' => builder.append('\t')
                case 'u' =>
                  if pos + 4 > source.length then fail("invalid unicode escape")
                  val hex = source.substring(pos, pos + 4)
                  pos += 4
                  builder.append(Integer.parseInt(hex, 16).toChar)
                case other => fail(s"invalid escape '\\$other'")
            case other => builder.append(other)
        builder.toString

      private def parseNumber(): Value =
        val start = pos
        if !atEnd && source.charAt(pos) == '-' then pos += 1
        while !atEnd && isNumberChar(source.charAt(pos)) do pos += 1
        Num(source.substring(start, pos).toDouble)

      private def isNumberChar(c: Char): Boolean =
        (c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-'

  def shutdown(): Unit = synchronized {
    servers.foreach(_.close())
    servers.clear()
    threadServer.remove()
  }

  private def server(compilerMain: String): CompilerServer = synchronized {
    threadServer.get() match
      case server: CompilerServer if server.compilerMain == compilerMain => server
      case server: CompilerServer =>
        server.close()
        servers -= server
        val next = new CompilerServer(compilerMain, nodeFlags)
        servers += next
        threadServer.set(next)
        next
      case null =>
        val next = new CompilerServer(compilerMain, nodeFlags)
        servers += next
        threadServer.set(next)
        next
  }

  private def nodeFlags: Seq[String] =
    sys.props.get(NodeFlagsProperty).toSeq
      .flatMap(_.split(',').toSeq)
      .filter(_.nonEmpty)

  private def withExtraClasspath(args: Array[String]): Array[String] =
    sys.props.get(ExtraClasspathProperty).filter(_.nonEmpty) match
      case Some(extraClasspath) =>
        val index = args.indexOf("-classpath")
        if index >= 0 && index + 1 < args.length then
          args.updated(index + 1, args(index + 1) + File.pathSeparator + extraClasspath)
        else
          Array("-classpath", extraClasspath) ++ args
      case None =>
        args

  private final class CompilerServer(val compilerMain: String, nodeFlags: Seq[String]):
    private val nextId = AtomicInteger()
    private val launcher = Files.createTempFile("sjs-compiler-test-server", ".mjs")
    Files.writeString(launcher, serverScript, StandardCharsets.UTF_8)
    launcher.toFile.deleteOnExit()

    private val process =
      ProcessBuilder((Seq("node") ++ nodeFlags ++ Seq(launcher.toString, compilerMain)).asJava)
        .redirectErrorStream(true)
        .start()
    private val stdout =
      BufferedReader(InputStreamReader(process.getInputStream, StandardCharsets.UTF_8))
    private val stdin =
      BufferedWriter(OutputStreamWriter(process.getOutputStream, StandardCharsets.UTF_8))

    private val ready = stdout.readLine()
    if ready != """{"ready":true}""" then
      throw IllegalStateException(s"$CompilerName server did not start. stdout: $ready")

    def run(args: Array[String]): (Int, String) = synchronized {
      val id = nextId.incrementAndGet()
      stdin.write(request(id, args))
      stdin.newLine()
      stdin.flush()

      val line = stdout.readLine()
      if line == null then
        throw IllegalStateException(s"$CompilerName server stopped unexpectedly.")

      line match
        case Response(responseId, code, encodedOutput) if responseId.nn.toInt == id =>
          val output = String(Base64.getUrlDecoder.decode(encodedOutput.nn), StandardCharsets.UTF_8)
          code.nn.toInt -> output
        case _ =>
          throw IllegalStateException(s"Unexpected $CompilerName server response: $line")
    }

    /** Send a structured request and return `(code, decodedOutput)`, where
     *  `code` is the compile exit code (or negative on an infrastructure
     *  failure) and `decodedOutput` is the base64url-decoded JSON payload.
     *  Transport is identical to [[run]]; only the request framing differs.
     */
    def runStructured(setupArgs: Array[String], sources: Array[String]): (Int, String) = synchronized {
      val id = nextId.incrementAndGet()
      stdin.write(structuredRequest(id, setupArgs, sources))
      stdin.newLine()
      stdin.flush()

      val line = stdout.readLine()
      if line == null then
        throw IllegalStateException(s"$CompilerName server stopped unexpectedly.")

      line match
        case Response(responseId, code, encodedOutput) if responseId.nn.toInt == id =>
          val output = String(Base64.getUrlDecoder.decode(encodedOutput.nn), StandardCharsets.UTF_8)
          code.nn.toInt -> output
        case _ =>
          throw IllegalStateException(s"Unexpected $CompilerName server response: $line")
    }

    def close(): Unit =
      try
        stdin.write("""{"type":"shutdown"}""")
        stdin.newLine()
        stdin.flush()
      catch case _: Exception => ()

      if !process.waitFor(5, TimeUnit.SECONDS) then
        process.destroyForcibly()
      Files.deleteIfExists(launcher)

  private def request(id: Int, args: Array[String]): String =
    args.iterator.map(jsonString).mkString(s"""{"id":$id,"args":[""", ",", "]}")

  private def structuredRequest(id: Int, setupArgs: Array[String], sources: Array[String]): String =
    val args = setupArgs.iterator.map(jsonString).mkString("[", ",", "]")
    val sourcePaths = sources.iterator.map(jsonString).mkString("[", ",", "]")
    s"""{"id":$id,"structured":true,"args":$args,"sources":$sourcePaths}"""

  private def jsonString(value: String): String =
    value.iterator.map {
      case '\\' => "\\\\"
      case '"' => "\\\""
      case '\n' => "\\n"
      case '\r' => "\\r"
      case '\t' => "\\t"
      case c if c < ' ' => f"\\u${c.toInt}%04x"
      case c => c.toString
    }.mkString("\"", "", "\"")

  private val serverScript =
    """import { pathToFileURL } from "node:url";
      |import readline from "node:readline";
      |
      |const compilerMain = process.argv[2];
      |const compiler = await import(pathToFileURL(compilerMain).href);
      |const originalStdoutWrite = process.stdout.write.bind(process.stdout);
      |const originalStderrWrite = process.stderr.write.bind(process.stderr);
      |
      |function send(value) {
      |  originalStdoutWrite(JSON.stringify(value) + "\n");
      |}
      |
      |function formatError(error) {
      |  return error && error.stack ? String(error.stack) : String(error);
      |}
      |
      |function makeCapture() {
      |  let output = "";
      |  const write = (chunk, encoding, callback) => {
      |    output += Buffer.isBuffer(chunk)
      |      ? chunk.toString(typeof encoding === "string" ? encoding : "utf8")
      |      : String(chunk);
      |    if (typeof encoding === "function") encoding();
      |    if (typeof callback === "function") callback();
      |    return true;
      |  };
      |  return { write, output: () => output };
      |}
      |
      |send({ ready: true });
      |
      |const lines = readline.createInterface({ input: process.stdin, crlfDelay: Infinity });
      |for await (const line of lines) {
      |  const request = JSON.parse(line);
      |  if (request.type === "shutdown") break;
      |
      |  if (request.structured) {
      |    const structuredCapture = makeCapture();
      |    process.stdout.write = structuredCapture.write;
      |    process.stderr.write = structuredCapture.write;
      |    let result = null;
      |    let failure = null;
      |    try {
      |      result = await compiler.compileScala3SjsAsync(request.args, request.sources);
      |    } catch (error) {
      |      failure = formatError(error);
      |    } finally {
      |      process.stdout.write = originalStdoutWrite;
      |      process.stderr.write = originalStderrWrite;
      |    }
      |    if (failure !== null) {
      |      send({
      |        id: request.id,
      |        code: -1,
      |        output: Buffer.from(JSON.stringify({ infraError: failure }), "utf8").toString("base64url")
      |      });
      |    } else {
      |      const exitCode = typeof result.exitCode === "number" ? result.exitCode : 1;
      |      send({
      |        id: request.id,
      |        code: exitCode,
      |        output: Buffer.from(JSON.stringify(result), "utf8").toString("base64url")
      |      });
      |    }
      |    continue;
      |  }
      |
      |  const capture = makeCapture();
      |  process.stdout.write = capture.write;
      |  process.stderr.write = capture.write;
      |
      |  let code = 1;
      |  try {
      |    code = await compiler.runScala3CompilerSJSAsync(request.args);
      |  } catch (error) {
      |    code = 1;
      |    process.stderr.write(formatError(error) + "\n");
      |  } finally {
      |    process.stdout.write = originalStdoutWrite;
      |    process.stderr.write = originalStderrWrite;
      |  }
      |
      |  send({
      |    id: request.id,
      |    code,
      |    output: Buffer.from(capture.output(), "utf8").toString("base64url")
      |  });
      |}
      |""".stripMargin
end SjsCompilerBridge
