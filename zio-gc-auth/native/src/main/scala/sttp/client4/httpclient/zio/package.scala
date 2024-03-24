package sttp.client4.httpclient

import _root_.zio.Task
import sttp.client4.GenericBackend

package object zio {
  type SttpClient = GenericBackend[Task, Any]
}
