package com.anymindgroup.pubsub

import com.anymindgroup.pubsub.OrderingKey
import com.anymindgroup.pubsub.PubsubTestSupport.topicWithSubscriptionGen

import zio.stream.ZSink
import zio.test.*
import zio.{Chunk, NonEmptyChunk, RIO, Scope, ZIO, durationInt}

// integration tests that need to run agains an emulator
object PubsubIntegrationSpec {
  def spec(
    // Publisher implementation to test against
    publisherImpl: (PubsubConnectionConfig, TopicName) => RIO[Scope, Publisher[Any, String]],
    // Subscriber implementation to test against
    subscriberImpl: PubsubConnectionConfig => RIO[Scope, Subscriber],
  ): Spec[Scope, Throwable] =
    suite(s"Pub/Sub integration spec")(
      test(s"Published and consumed messages are matching (single message)") {
        ZIO.scoped:
          for
            (topic, sub) <- topicWithSubscriptionGen("any").runHead.map(_.get)
            _            <- PubsubTestSupport.createTopicWithSubscription(topic, sub)
            connection   <- ZIO.service[PubsubConnectionConfig]
            publisher    <- publisherImpl(connection, topic)
            subscriber   <- subscriberImpl(connection)
            message      <- publishMessageGen.runHead.map(_.get)
            publishedId  <- publisher.publish(message)
            consumed     <-
              subscriber.subscribe(sub, Serde.utf8String).map(_._1).take(1).runCollect.map(_.head)
            _ <- assertTrue(consumed.messageId == publishedId)
            _ <- assertTrue(consumed.data == message.data)
            _ <- assertTrue(consumed.attributes == message.attributes)
            _ <- assertTrue(consumed.orderingKey == message.orderingKey)
          yield assertCompletes
      },
      test(s"Published and consumed messages are matching (multiple messages)") {
        ZIO.scoped:
          for
            (topic, sub) <- topicWithSubscriptionGen("any").runHead.map(_.get)
            _            <- PubsubTestSupport.createTopicWithSubscription(topic, sub)
            connection   <- ZIO.service[PubsubConnectionConfig]
            publisher    <- publisherImpl(connection, topic)
            subscriber   <- subscriberImpl(connection)
            messages     <- publishMessagesGen(5, 20).runHead.map(_.get)
            publishedIds <- publisher.publish(messages)
            _            <- assertTrue(publishedIds.length == messages.length)
            consumed     <- subscriber.subscribe(sub, Serde.utf8String).take(messages.length).runCollect
            _            <- assertTrue(consumed.length == publishedIds.length)
          yield assertCompletes
      },
      test("Subscriber messages are not acked or nacked") {
        val ackDeadlineSeconds = 10 // minimum

        ZIO.scoped:
          for
            (topic, sub) <- topicWithSubscriptionGen("any").runHead.map(_.get)
            _            <-
              PubsubTestSupport.createTopicWithSubscription(topic, sub, ackDeadlineSeconds = Some(ackDeadlineSeconds))
            connection <- ZIO.service[PubsubConnectionConfig]
            subscriber <- subscriberImpl(connection)
            message    <- publishMessagesGen(1, 1).runHead.map(_.get.head)
            mId        <- PubsubTestSupport.publishEvent(message, topic)
            res        <- subscriber
                     .subscribe(sub, Serde.utf8String)
                     // run consumer until the same message was re-delivered due to ack deadline exceeded
                     .run(ZSink.foldUntil(Chunk.empty[MessageId], 2)(_ :+ _._1.messageId))
            _ <- assertTrue(res == Chunk(mId, mId))
          yield assertCompletes
      },
      test("Subscriber messages are nacked") {
        ZIO.scoped:
          for
            (topic, sub) <- topicWithSubscriptionGen("any").runHead.map(_.get)
            _            <- PubsubTestSupport.createTopicWithSubscription(topic, sub)
            connection   <- ZIO.service[PubsubConnectionConfig]
            subscriber   <- subscriberImpl(connection)
            message      <- publishMessagesGen(1, 1).runHead.map(_.get.head)
            mId          <- PubsubTestSupport.publishEvent(message, topic)
            res          <- subscriber
                     .subscribe(sub, Serde.utf8String)
                     .tap(_._2.nack()) // nack all messages
                     // run consumer until the same message was re-delivered
                     .run(ZSink.foldUntil(Chunk.empty[MessageId], 2)(_ :+ _._1.messageId))
            _ <- assertTrue(res == Chunk(mId, mId))
          yield assertCompletes
      },
      test("Subscriber messages are acked") {
        val ackDeadlineSeconds = 10 // minimum
        ZIO.scoped:
          for
            (topic, sub) <- topicWithSubscriptionGen("any").runHead.map(_.get)
            _            <-
              PubsubTestSupport.createTopicWithSubscription(topic, sub, ackDeadlineSeconds = Some(ackDeadlineSeconds))
            connection <- ZIO.service[PubsubConnectionConfig]
            subscriber <- subscriberImpl(connection)
            message    <- publishMessagesGen(1, 1).runHead.map(_.get.head)
            _          <- PubsubTestSupport.publishEvent(message, topic)
            consumed   <-
              subscriber
                .subscribe(sub, Serde.utf8String)
                .tap(_._2.ack())
                .interruptAfter(
                  (ackDeadlineSeconds + 3).seconds
                ) // run consumer for longer than the ack deadline to confirm that messages are delivered once due to being acknowledged
                .runCount
            _ <- assertTrue(consumed == 1)
          yield assertCompletes
      },
    ).provide(
      PubsubTestSupport.emulatorBackendLayer()
    ) @@ TestAspect.withLiveClock @@ TestAspect.nondeterministic @@ TestAspect.timeout(60.seconds)

  private val publishMessageGen = for
    data        <- Gen.alphaNumericStringBounded(1, 500) // data can't be empty
    orderingKey <- Gen.option(Gen.alphaNumericString).map(_.flatMap(OrderingKey.fromString))
    attributes  <- Gen.mapOfBounded(0, 10)(Gen.alphaNumericString, Gen.alphaNumericString)
  yield PublishMessage(
    data = data,
    orderingKey = orderingKey,
    attributes = attributes,
  )

  private def publishMessagesGen(min: Int, max: Int) =
    Gen.chunkOfBounded(min, max)(publishMessageGen).map(NonEmptyChunk.fromChunk(_).get)
}
