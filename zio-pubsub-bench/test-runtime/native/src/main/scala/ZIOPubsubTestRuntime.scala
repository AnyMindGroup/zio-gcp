package com.anymindgroup.pubsub

import zio.*
import java.util.concurrent.Executors

trait ZIOPubsubTestRuntime:
  def setRuntime: ZLayer[Any, Throwable, Unit] =
    Runtime.setExecutor(
      Executor.fromJavaExecutor(
        Executors.newCachedThreadPool()
      )
    )
