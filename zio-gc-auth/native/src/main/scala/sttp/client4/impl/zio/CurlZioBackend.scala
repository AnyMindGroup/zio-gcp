package sttp.client4.impl.zio

import _root_.zio.*
import sttp.client4.*
import sttp.client4.curl.AbstractSyncCurlBackend

class CurlZioBackend private (verbose: Boolean)
    extends AbstractSyncCurlBackend[Task](new RIOMonadAsyncError, verbose)
    with Backend[Task]

object CurlZioBackend {
  def apply(verbose: Boolean = false): Backend[Task] = new CurlZioBackend(verbose)
}
