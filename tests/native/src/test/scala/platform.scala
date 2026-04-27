package com.anymindgroup.pubsub
package http

import sttp.capabilities.zio.ZioStreams
import sttp.client4.impl.zio.RIOMonadAsyncError
import sttp.client4.testing.StreamBackendStub

import zio.Task

def platformStub = StreamBackendStub[Task, ZioStreams](new RIOMonadAsyncError[Any])
