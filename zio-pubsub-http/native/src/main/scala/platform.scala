package com.anymindgroup.pubsub.http

import sttp.capabilities.Effect

import zio.Task

type HttpPlatformBackend      = sttp.client4.Backend[Task]
type HttpPlatformCapabilities = Effect[Task]
