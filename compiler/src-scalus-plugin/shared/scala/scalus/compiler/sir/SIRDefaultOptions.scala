package scalus.compiler.sir
import scala.language.unsafeNulls

enum TargetLoweringBackend:
    case ScottEncodingLowering
    case SumOfProductsLowering
    case SirToUplcV3Lowering

/** Default compiler options for SIR processing. Here to have a single place for default options,
  * which is shared between the compiler plugin and the core library.
  */
object SIRDefaultOptions {

    val targetLoweringBackend: TargetLoweringBackend = TargetLoweringBackend.SirToUplcV3Lowering
    val generateErrorTraces: Boolean = true
    val removeTraces: Boolean = false
    val optimizeUplc: Boolean = false

    // debugging options
    val writeSirToFile: Boolean = false
    val debugLevel: Int = 0

}
