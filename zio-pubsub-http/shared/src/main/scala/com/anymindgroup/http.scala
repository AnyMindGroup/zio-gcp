package com.anymindgroup.pubsub.http

import com.anymindgroup.gcp.auth.*
import com.anymindgroup.pubsub.{PubsubConnectionConfig, Serializer, TopicName}

import zio.{Schedule, Scope, ZIO}

// http backend with authentication
// e.g. for usage with Pub/Sub Admin API
def makeAuthedBackend(
  connection: PubsubConnectionConfig = PubsubConnectionConfig.Cloud,
  authConfig: AuthConfig = AuthConfig.default,
): ZIO[Scope, Throwable, AuthedBackend] = connection match
  case PubsubConnectionConfig.Cloud =>
    defaultAccessTokenBackend(
      lookupComputeMetadataFirst = authConfig.lookupComputeMetadataFirst,
      refreshRetrySchedule = authConfig.tokenRefreshRetrySchedule,
      refreshAtExpirationPercent = authConfig.tokenRefreshAtExpirationPercent,
    )
  case e: PubsubConnectionConfig.Emulator => EmulatorBackend.withDefaultBackend(e)

def makeTopicPublisher[R, E](
  topicName: TopicName,
  serializer: Serializer[R, E],
  connection: PubsubConnectionConfig = PubsubConnectionConfig.Cloud,
  backend: Option[AuthedBackend | HttpPlatformBackend] = None,
  authConfig: AuthConfig = AuthConfig.default,
): ZIO[Scope, Throwable, HttpTopicPublisher[R, E]] =
  backend match
    case None =>
      HttpTopicPublisher.makeWithDefaultBackend(
        connection = connection,
        topicName = topicName,
        serializer = serializer,
        authConfig = authConfig,
      )
    case Some(b: AuthedBackend) =>
      ZIO.succeed(
        HttpTopicPublisher.makeFromAuthedBackend(
          topicName = topicName,
          serializer = serializer,
          authedBackend = b,
        )
      )
    case Some(b) =>
      HttpTopicPublisher.makeWithDefaultTokenProvider(
        topicName = topicName,
        serializer = serializer,
        backend = b,
        authConfig = authConfig,
      )

def makeSubscriber(
  backend: Option[AuthedBackend | HttpPlatformBackend] = None,
  connection: PubsubConnectionConfig = PubsubConnectionConfig.Cloud,
  maxMessagesPerPull: Int = HttpSubscriber.defaults.maxMessagesPerPull,
  retrySchedule: Schedule[Any, Throwable, ?] = HttpSubscriber.defaults.retrySchedule,
  authConfig: AuthConfig = AuthConfig.default,
): ZIO[Scope, Throwable, HttpSubscriber] = backend match
  case None =>
    HttpSubscriber.makeWithDefaultBackend(
      connection = connection,
      maxMessagesPerPull = maxMessagesPerPull,
      retrySchedule = retrySchedule,
      authConfig = authConfig,
    )
  case Some(b: AuthedBackend) =>
    HttpSubscriber.makeFromAuthedBackend(
      authedBackend = b,
      maxMessagesPerPull = maxMessagesPerPull,
      retrySchedule = retrySchedule,
    )
  case Some(b) =>
    HttpSubscriber.makeWithDefaultTokenProvider(
      backend = b,
      maxMessagesPerPull = maxMessagesPerPull,
      retrySchedule = retrySchedule,
      authConfig = authConfig,
    )
