package com.anymindgroup.pubsub
package google

import java.time.Instant

import scala.jdk.CollectionConverters.*

import com.google.pubsub.v1.ReceivedMessage as GReceivedMessage

import zio.stream.ZStream
import zio.{Chunk, RIO, Schedule, Scope, ZIO, ZLayer}

object Subscriber {
  type StreamAckDeadlineSeconds = Int

  def makeStreamingPullSubscriber(
    connection: PubsubConnectionConfig,
    retrySchedule: Schedule[Any, Throwable, ?] = StreamingPullSubscriber.defaultRetrySchedule,
  ): RIO[Scope, Subscriber] = ZIO.serviceWith[Scope](scope =>
    new Subscriber {
      override def subscribeRaw(subscriptionName: SubscriptionName): ZStream[Any, Throwable, RawReceipt] =
        ZStream
          .fromZIO(
            makeRawStreamingPullSubscription(
              connection = connection,
              subscriptionName = subscriptionName,
              retrySchedule = retrySchedule,
            ).provide(ZLayer.succeed(scope))
          )
          .flatten
    }
  )

  private[pubsub] def makeRawStreamingPullSubscription(
    subscriptionName: SubscriptionName,
    connection: PubsubConnectionConfig,
    retrySchedule: Schedule[Any, Throwable, ?] = StreamingPullSubscriber.defaultRetrySchedule,
  ): RIO[Scope, ZStream[Any, Throwable, RawReceipt]] =
    makeGoogleStreamingPullSubscription(
      connection = connection,
      subscriptionName = subscriptionName,
      retrySchedule = retrySchedule,
    ).map {
      _.map { case (gMessage, ackReply) =>
        (toRawReceivedMessage(gMessage), ackReply)
      }
    }

  private[pubsub] def toRawReceivedMessage(rm: GReceivedMessage): ReceivedMessage.Raw = {
    val msg = rm.getMessage
    val ts  = msg.getPublishTime()

    ReceivedMessage(
      data = Chunk.fromArray(msg.getData.toByteArray()),
      meta = ReceivedMessage.Metadata(
        messageId = MessageId(msg.getMessageId()),
        ackId = AckId(rm.getAckId()),
        orderingKey = OrderingKey.fromString(msg.getOrderingKey()),
        publishTime = Instant
          .ofEpochSecond(ts.getSeconds())
          .plusNanos(ts.getNanos().toLong),
        attributes = msg.getAttributesMap.asScala.toMap,
        deliveryAttempt = rm.getDeliveryAttempt(),
      ),
    )
  }

  private[pubsub] def makeGoogleStreamingPullSubscription(
    subscriptionName: SubscriptionName,
    retrySchedule: Schedule[Any, Throwable, ?],
    connection: PubsubConnectionConfig,
  ): RIO[Scope, GoogleStream] =
    StreamingPullSubscriber.makeRawStream(
      connection = connection,
      subscriptionName = subscriptionName,
      retrySchedule = retrySchedule,
    )
}
