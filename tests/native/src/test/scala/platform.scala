package com.anymindgroup.pubsub
package http

import zio.Task

import sttp.capabilities.zio.ZioStreams
import sttp.client4.impl.zio.RIOMonadAsyncError
import sttp.client4.testing.StreamBackendStub

def platformStub = StreamBackendStub[Task, ZioStreams](new RIOMonadAsyncError[Any])
