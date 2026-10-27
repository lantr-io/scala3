package dotty.tools.dotc.plugins

/** Plugins linked into the Scala.js compiler binary, which cannot load plugin jars.
 *
 *  Empty by default. A build that links a plugin's sources into the compiler lists the plugin here,
 *  e.g. `List(new my.plugin.Plugin)`, and its phases run as they would on the JVM.
 */
object StaticPlugins:
  def all: List[Plugin] = Nil
