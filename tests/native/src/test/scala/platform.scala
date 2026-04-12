package com.anymindgroup.pubsub
package http

import sttp.client4.impl.zio.RIOMonadAsyncError
import sttp.client4.testing.BackendStub

import zio.Task

def platformStub = BackendStub[Task](new RIOMonadAsyncError[Any])
