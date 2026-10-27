package dotty.tools
package dotc

import config.Platform
import config.SJSPlatform
import core.Contexts.Context
import core.Contexts.ContextBase
import core.Phases.Phase

final class JSContextBase extends ContextBase:
  override protected def newPlatform(using Context): Platform =
    new SJSPlatform

  override def pluginDescriptions(using Context): String = ""

  override def pluginOptionsHelp(using Context): String = ""
