package dotty.tools.dotc.plugins

/** Plugins linked into the Scala.js compiler binary, which cannot load plugin jars. */
object StaticPlugins:
  def all: List[Plugin] = List(new scalus.compiler.plugin.Plugin)
