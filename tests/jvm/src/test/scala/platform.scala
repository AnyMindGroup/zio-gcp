package com.anymindgroup.pubsub
package http

import sttp.capabilities.zio.ZioStreams
import sttp.client4.impl.zio.RIOMonadAsyncError
import sttp.client4.testing.WebSocketStreamBackendStub

import zio.Task

def platformStub = WebSocketStreamBackendStub[Task, ZioStreams](new RIOMonadAsyncError[Any])
