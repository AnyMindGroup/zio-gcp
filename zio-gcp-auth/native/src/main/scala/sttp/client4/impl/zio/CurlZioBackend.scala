package sttp.client4.impl.zio

import _root_.zio.*
import sttp.client4.*

class CurlZioBackend private (verbose: Boolean) extends sttp.client4.curl.TaskCurlBackend(verbose) with Backend[Task]

object CurlZioBackend {
  def apply(verbose: Boolean = false): Backend[Task] = new CurlZioBackend(verbose)
}
