package com.anymindgroup.pubsub.http

import sttp.capabilities.zio.ZioStreams
import sttp.capabilities.{Effect, WebSockets}

import zio.Task

type HttpPlatformBackend      = sttp.client4.WebSocketStreamBackend[Task, ZioStreams]
type HttpPlatformCapabilities = ZioStreams & WebSockets & Effect[Task]
