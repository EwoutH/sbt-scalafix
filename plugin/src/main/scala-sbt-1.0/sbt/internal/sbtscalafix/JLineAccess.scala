package sbt.internal.sbtscalafix

/** Helper class to access sbt's JLine instance */
trait JLineAccess {
  def terminalWidth: Int = sbt.internal.util.JLine.usingTerminal(_.getWidth)
}
