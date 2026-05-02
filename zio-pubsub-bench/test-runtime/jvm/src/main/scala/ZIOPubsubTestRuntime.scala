package com.anymindgroup.pubsub

import zio.*

trait ZIOPubsubTestRuntime:
  def setRuntime: ZLayer[Any, Throwable, Unit] = ZLayer.unit
  // Runtime.enableLoomBasedExecutor ++ Runtime.enableLoomBasedBlockingExecutor
