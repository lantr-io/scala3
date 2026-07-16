package dotty.tools
package dotc
package reporting

import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.util.SourcePosition

import scala.scalajs.js
import scala.util.control.NonFatal

/** Conversion of compiler [[Diagnostic]]s to the plain JS objects consumed by the
 *  structured browser compile API. Every field of the produced object is always
 *  present; absent values serialize as JS `null` (never `undefined`) so they survive
 *  `postMessage`/`JSON.stringify`.
 */
object JSDiagnostics:

  /** A JS `null` typed as `js.Any`; serializes as `null`. */
  private val JsNull: js.Any = null.asInstanceOf[js.Any]

  /** The ANSI escape code point (`ESC`). */
  private final val Esc = 27

  /** Remove ANSI SGR escape sequences (coloring) from `s`. The compiler emits
   *  `ESC[..m` sequences (`scala.io.AnsiColor`), stripped here by a plain scan so the
   *  produced message/rendering carry no control characters.
   */
  private def stripAnsi(s: String): String =
    if s.indexOf(Esc) < 0 then s
    else
      val n = s.length
      val out = new StringBuilder(n)
      var i = 0
      while i < n do
        val c = s.charAt(i)
        if c.toInt == Esc && i + 1 < n && s.charAt(i + 1) == '[' then
          i += 2
          while i < n && s.charAt(i) != 'm' do i += 1
          if i < n then i += 1 // drop the terminating 'm'
        else
          out.append(c)
          i += 1
      out.toString

  private def severityOf(level: Int): String =
    level match
      case Diagnostic.ERROR   => "error"
      case Diagnostic.WARNING => "warning"
      case _                  => "info"

  private def jsString(s: String): js.Any = s

  private def forceMessage(dia: Diagnostic): String =
    try stripAnsi(dia.msg.message)
    catch case NonFatal(_) => ""

  /** The `code`/`name` pair for a message: `("E007", "TypeMismatch")` style, or
   *  `(null, null)` when the message has no active, numbered error id.
   */
  private def codeAndName(msg: Message): (js.Any, js.Any) =
    try
      val errorId = msg.errorId
      val number = errorId.errorNumber
      if errorId == ErrorMessageID.NoExplanationID || number < 0 || !errorId.isActive then
        (JsNull, JsNull)
      else
        val ns = number.toString
        val code = "E" + ("0" * (3 - ns.length)) + ns
        val name = errorId.productPrefix.stripSuffix("ID")
        (jsString(code), jsString(name))
    catch case NonFatal(_) => (JsNull, JsNull)

  /** Convert `dia` to a plain JS diagnostic object, using `rendered` as the full
   *  console-style rendering. Both message and rendering are ANSI-stripped. Position
   *  fields are extracted defensively: a broken position yields `null` fields but never
   *  aborts the conversion.
   */
  def toJSDiagnostic(dia: Diagnostic, rendered: String): js.Dynamic =
    val pos = dia.pos

    val hasPos =
      try pos.exists && pos.source.exists
      catch case NonFatal(_) => false

    def posInt(compute: SourcePosition => Int): js.Any =
      if !hasPos then JsNull
      else
        try (compute(pos): js.Any)
        catch case NonFatal(_) => JsNull

    val file: js.Any =
      if !hasPos then JsNull
      else
        try jsString(pos.source.file.path)
        catch case NonFatal(_) => JsNull

    val (code, name) = codeAndName(dia.msg)

    js.Dynamic.literal(
      severity = severityOf(dia.level),
      message = forceMessage(dia),
      rendered = stripAnsi(rendered),
      file = file,
      line = posInt(_.line),
      column = posInt(_.column),
      endLine = posInt(_.endLine),
      endColumn = posInt(_.endColumn),
      offset = posInt(_.start),
      endOffset = posInt(_.end),
      code = code,
      name = name,
    )

  /** A synthetic `error` diagnostic carrying only `message`, used when a compile round is
   *  interrupted before it could report any diagnostic of its own.
   */
  def syntheticErrorDiagnostic(message: String): js.Dynamic =
    val text = stripAnsi(message)
    js.Dynamic.literal(
      severity = "error",
      message = text,
      rendered = text,
      file = JsNull,
      line = JsNull,
      column = JsNull,
      endLine = JsNull,
      endColumn = JsNull,
      offset = JsNull,
      endOffset = JsNull,
      code = JsNull,
      name = JsNull,
    )
end JSDiagnostics

/** An [[AbstractReporter]] (same mixin stack as [[ConsoleReporter]], so `-Wconf`/`-Werror`
 *  and nonsensical-message handling match console behavior) that converts each diagnostic
 *  to a plain JS object immediately — forcing its message and rendering under the current
 *  `Context` — and buffers it in report order. Used by the structured browser compile API.
 */
final class CollectingReporter extends AbstractReporter:
  private val buffer = new js.Array[js.Dynamic]()

  override def doReport(dia: Diagnostic)(using Context): Unit =
    val rendered =
      try messageAndPos(dia)
      catch case NonFatal(_) => ""
    buffer.push(JSDiagnostics.toJSDiagnostic(dia, rendered))
    ()

  /** Suppress the "N errors found" summary so it never becomes a diagnostic. */
  override def printSummary()(using Context): Unit = ()

  /** The diagnostics collected so far, in report order (live buffer). */
  def collectedDiagnostics: js.Array[js.Dynamic] = buffer
end CollectingReporter
