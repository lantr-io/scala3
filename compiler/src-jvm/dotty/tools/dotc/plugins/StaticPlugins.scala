package dotty.tools.dotc.plugins

/** Plugins linked into the compiler binary. None on the JVM: plugins load from jars. */
object StaticPlugins:
  def all: List[Plugin] = Nil
