package com.anymindgroup.pubsub
package google

import java.util.concurrent.TimeUnit

import com.google.api.gax.core.{BackgroundResource, CredentialsProvider, FixedExecutorProvider, NoCredentialsProvider}
import com.google.api.gax.grpc.GrpcTransportChannel
import com.google.api.gax.rpc.{ClientSettings, FixedTransportChannelProvider, StubSettings, TransportChannelProvider}
import com.google.pubsub.v1.ReceivedMessage as GReceivedMessage
import io.grpc.{ManagedChannel, ManagedChannelBuilder}

import zio.stream.ZStream
import zio.{Clock, RIO, Schedule, Scope, Task, ZIO}

private[google] type GoogleReceipt = (GReceivedMessage, AckReply)
private[google] type GoogleStream  = ZStream[Any, Throwable, GoogleReceipt]

private def backgroundExecutorProvider =
  Clock.scheduler.map(_.asScheduledExecutorService).map(FixedExecutorProvider.create)

private def acquireBackgroundReource[R <: BackgroundResource](
  acquire: => Task[R]
) = ZIO.acquireRelease(acquire)(resource =>
  ZIO.attempt {
    resource.shutdown()
    resource.awaitTermination(30, TimeUnit.SECONDS)
  }.ignore
)

def createEmulatorSettings(
  config: PubsubConnectionConfig.Emulator
): RIO[Scope, (TransportChannelProvider, CredentialsProvider)] = for {
  channel <- acquireBackgroundReource(ZIO.attempt {
               val channel: ManagedChannel =
                 ManagedChannelBuilder.forTarget(s"${config.host}:${config.port}").usePlaintext().build()
               GrpcTransportChannel.create(channel)
             })
  channelProvider    <- ZIO.attempt(FixedTransportChannelProvider.create(channel))
  credentialsProvider = NoCredentialsProvider.create
} yield (channelProvider, credentialsProvider)

def createClient[
  Settings <: ClientSettings[Settings],
  SettingsBuilder <: ClientSettings.Builder[Settings, SettingsBuilder],
  Client <: BackgroundResource,
](
  builder: => SettingsBuilder,
  create: Settings => Client,
  connection: PubsubConnectionConfig = PubsubConnectionConfig.Cloud,
): ZIO[Scope, Throwable, Client] =
  backgroundExecutorProvider.flatMap: executor =>
    connection match
      case PubsubConnectionConfig.Cloud =>
        acquireBackgroundReource(
          ZIO.attempt(create(builder.setBackgroundExecutorProvider(executor).build().asInstanceOf[Settings]))
        )
      case conf: PubsubConnectionConfig.Emulator =>
        for {
          (channel, credentials) <- createEmulatorSettings(conf)
          client                 <- acquireBackgroundReource(
                      ZIO.attempt(
                        create(
                          builder
                            .setBackgroundExecutorProvider(executor)
                            .setCredentialsProvider(credentials)
                            .setTransportChannelProvider(channel)
                            .build()
                            .asInstanceOf[Settings]
                        )
                      )
                    )
        } yield client

private def createStub[
  Settings <: StubSettings[Settings],
  SettingsBuilder <: StubSettings.Builder[Settings, SettingsBuilder],
  Client <: BackgroundResource,
](
  connection: PubsubConnectionConfig,
  builder: => SettingsBuilder,
  create: Settings => Client,
): ZIO[Scope, Throwable, Client] =
  backgroundExecutorProvider.flatMap: executor =>
    connection match
      case PubsubConnectionConfig.Cloud =>
        acquireBackgroundReource(
          ZIO.attempt(
            create(
              builder
                .setBackgroundExecutorProvider(executor)
                .build()
                .asInstanceOf[Settings]
            )
          )
        )
      case conf: PubsubConnectionConfig.Emulator =>
        for {
          (channel, credentials) <- createEmulatorSettings(conf)
          client                 <- acquireBackgroundReource(
                      ZIO.attempt(
                        create(
                          builder
                            .setBackgroundExecutorProvider(executor)
                            .setCredentialsProvider(credentials)
                            .setTransportChannelProvider(channel)
                            .build()
                            .asInstanceOf[Settings]
                        )
                      )
                    )
        } yield client

def makeTopicPublisher[R, E](
  topicName: TopicName,
  serializer: Serializer[R, E],
  connection: PubsubConnectionConfig = PubsubConnectionConfig.Cloud,
): RIO[Scope, GoogleTopicPublisher[R, E]] =
  GoogleTopicPublisher.make(topicName = topicName, serializer = serializer, connection = connection)

def makeStreamingPullSubscriber(
  connection: PubsubConnectionConfig = PubsubConnectionConfig.Cloud,
  retrySchedule: Schedule[Any, Throwable, ?] = StreamingPullSubscriber.defaultRetrySchedule,
) = Subscriber.makeStreamingPullSubscriber(connection, retrySchedule)
