package com.anymindgroup.pubsub.google

import java.time.Instant

import scala.jdk.CollectionConverters.*

import com.anymindgroup.pubsub.*
import com.anymindgroup.pubsub.google.Subscriber as GSubscriber
import com.google.protobuf.Timestamp
import com.google.pubsub.v1.{PubsubMessage, ReceivedMessage as GReceivedMessage}

import zio.Scope
import zio.test.*
import zio.test.Assertion.*

object SubscriberSpec extends ZIOSpecDefault {

  val receivedMessageGen: Gen[Any, GReceivedMessage] = for
    ackId           <- Gen.uuid.map(_.toString())
    messageId       <- Gen.alphaNumericStringBounded(1, 20)
    deliveryAttempt <- Gen.int(0, Int.MaxValue)
    orderingKey     <- Gen.option(Gen.alphaNumericString)
    attrs           <- Gen.mapOfBounded(0, 20)(Gen.alphaNumericString, Gen.alphaNumericString)
    ts              <- Gen.long(0L, Int.MaxValue.toLong).map(Instant.ofEpochSecond)
  yield GReceivedMessage
    .newBuilder()
    .setMessage {
      val msgBuilder = PubsubMessage
        .newBuilder()
        .putAllAttributes(attrs.asJava)
        .setPublishTime(Timestamp.newBuilder.setSeconds(ts.getEpochSecond()).setNanos(ts.getNano()).build())
        .setMessageId(messageId)

      orderingKey.fold(msgBuilder)(k => msgBuilder.setOrderingKey(k)).build()
    }
    .setAckId(ackId)
    .setDeliveryAttempt(deliveryAttempt)
    .build()

  override def spec: Spec[TestEnvironment & Scope, Any] = suite("SubscriberSpec")(
    test("get meta data from ReceivedMessage") {
      check(receivedMessageGen) { sample =>
        val res = GSubscriber.toRawReceivedMessage(sample)
        for
          _ <- assertTrue(res.meta.ackId == AckId(sample.getAckId()))
          _ <- assertTrue(res.meta.messageId == MessageId(sample.getMessage.getMessageId()))
          _ <- assertTrue(
                 res.meta.orderingKey == OrderingKey.fromString(sample.getMessage.getOrderingKey())
               )
          _ <- assert(res.meta.attributes)(hasSameElements(sample.getMessage.getAttributesMap().asScala))
          _ <- assertTrue(res.meta.publishTime.getEpochSecond == sample.getMessage().getPublishTime().getSeconds())
          _ <- assertTrue(res.meta.publishTime.getNano == sample.getMessage().getPublishTime().getNanos())
          _ <- assertTrue(res.meta.deliveryAttempt == sample.getDeliveryAttempt())
          _ <- assertTrue(res.data.length == sample.getMessage().getData().size())
        yield assertCompletes
      }
    }
  )
}
