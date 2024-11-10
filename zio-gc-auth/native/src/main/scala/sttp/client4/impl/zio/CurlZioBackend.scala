package sttp.client4.impl.zio

import _root_.zio.*
import sttp.client4.*
import sttp.client4.curl.AbstractCurlBackend
import sttp.client4.curl.internal.CurlApi.*
import sttp.client4.curl.internal.CurlCode

class CurlZioBackend private (verbose: Boolean)
    extends AbstractCurlBackend[Task](new RIOMonadAsyncError, verbose)
    with Backend[Task] {

  override def performCurl(c: CurlHandle): Task[CurlCode.CurlCode] =
    ZIO.attemptBlocking(c.perform)
}

object CurlZioBackend {
  def apply(verbose: Boolean = false): Backend[Task] = new CurlZioBackend(verbose)
}
