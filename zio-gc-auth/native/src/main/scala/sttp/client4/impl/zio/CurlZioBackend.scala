package sttp.client4.impl.zio

import _root_.zio.*
import sttp.client4.*
import sttp.client4.curl.AbstractCurlBackend
import sttp.client4.curl.internal.CurlApi.*
import sttp.client4.curl.internal.CurlCode.CurlCode
import sttp.client4.wrappers.FollowRedirectsBackend

class CurlZioBackend[R] private (verbose: Boolean)
    extends AbstractCurlBackend[RIO[R, *]](new RIOMonadAsyncError, verbose)
    with Backend[RIO[R, *]] {

  override def performCurl(c: CurlHandle): Task[CurlCode] = ZIO.attemptBlocking(c.perform)

}

object CurlZioBackend {
  def apply[R](verbose: Boolean = false): Backend[RIO[R, *]] =
    FollowRedirectsBackend(new CurlZioBackend[R](verbose))
}
