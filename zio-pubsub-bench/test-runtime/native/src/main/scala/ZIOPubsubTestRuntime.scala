package com.anymindgroup.pubsub

import java.util.concurrent.Executors

import zio.*

trait ZIOPubsubTestRuntime:
  def setRuntime: ZLayer[Any, Throwable, Unit] =
    Runtime.setExecutor(
      Executor.fromJavaExecutor(
        Executors.newCachedThreadPool()
      )
    )
