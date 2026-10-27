package java.io

/** Minimal shim: Scala.js's javalib has no BufferedOutputStream. Writes go straight through. */
class BufferedOutputStream(out: OutputStream, size: Int) extends FilterOutputStream(out):
  def this(out: OutputStream) = this(out, 8192)

  override def write(b: Array[Byte], off: Int, len: Int): Unit =
    out.write(b, off, len)
