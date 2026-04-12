package com.anymindgroup.pubsub

import java.util.Base64

import com.anymindgroup.gcp.pubsub.v1.resources.projects as p
import com.anymindgroup.gcp.pubsub.v1.schemas as s
import com.anymindgroup.pubsub.http.{EmulatorBackend, HttpSubscriber}
import sttp.client4.Backend

import zio.test.Gen
import zio.{Chunk, RIO, Task, ZIO, ZLayer, durationInt}

object PubsubTestSupport {
  def emulatorConnectionConfig(
    host: String = sys.env.get("PUBSUB_EMULATOR_HOST").getOrElse("localhost"),
    port: Int = sys.env.get("PUBSUB_EMULATOR_PORT").flatMap(_.toIntOption).getOrElse(8085),
  ): PubsubConnectionConfig.Emulator =
    PubsubConnectionConfig.Emulator(host, port)

  def emulatorConnectionConfigLayer(
    config: PubsubConnectionConfig.Emulator = emulatorConnectionConfig()
  ): ZLayer[Any, Nothing, PubsubConnectionConfig.Emulator] =
    ZLayer.succeed(config)

  def emulatorBackendLayer(
    config: PubsubConnectionConfig.Emulator = emulatorConnectionConfig()
  ): ZLayer[Any, Throwable, Backend[Task] & PubsubConnectionConfig.Emulator] =
    ZLayer.scoped(EmulatorBackend.withDefaultBackend(config)) ++ ZLayer.succeed(config)

  def createTopicWithSubscription(
    topicName: TopicName,
    subscriptionName: SubscriptionName,
    ackDeadlineSeconds: Option[Int] = Some(10),
  ): RIO[Backend[Task], Unit] =
    createTopic(topicName) *> createSubscription(topicName, subscriptionName, ackDeadlineSeconds)

  def createSubscription(
    topicName: TopicName,
    subscriptionName: SubscriptionName,
    ackDeadlineSeconds: Option[Int] = Some(10),
  ): RIO[Backend[Task], Unit] =
    ZIO
      .serviceWithZIO[Backend[Task]](
        _.send(
          p.Subscriptions.create(
            projectsId = subscriptionName.projectId,
            subscriptionsId = subscriptionName.subscription,
            request = s.Subscription(
              name = subscriptionName.fullName,
              topic = topicName.fullName,
              ackDeadlineSeconds = ackDeadlineSeconds,
            ),
          )
        )
      )
      .unit

  def createTopic(topicName: TopicName): RIO[Backend[Task], Unit] =
    ZIO
      .serviceWithZIO[Backend[Task]](
        _.send(
          p.Topics.create(
            projectsId = topicName.projectId,
            topicsId = topicName.topic,
            request = s.Topic(name = topicName.fullName),
          )
        )
      )
      .unit

  def topicExists(topicName: TopicName): RIO[Backend[Task], Boolean] = for {
    topicAdmin <- ZIO.service[Backend[Task]]
    res        <- topicAdmin.send(p.Topics.get(projectsId = topicName.projectId, topicsId = topicName.topic))
  } yield res.body.isRight

  def publishEvent[E](
    event: E,
    topicName: TopicName,
    encode: E => Chunk[Byte] = (e: E) => Chunk.fromArray(e.toString.getBytes),
  ): RIO[Backend[Task], MessageId] =
    publishEvents(Seq(event), topicName, encode).map(_.head)

  def publishEvents[E](
    events: Seq[E],
    topicName: TopicName,
    encode: E => Chunk[Byte] = (e: E) => Chunk.fromArray(e.toString.getBytes),
  ): RIO[Backend[Task], Chunk[MessageId]] =
    ZIO.serviceWithZIO[Backend[Task]](
      _.send(
        p.Topics
          .publish(
            projectsId = topicName.projectId,
            topicsId = topicName.topic,
            request = s.PublishRequest(
              Chunk.fromIterable(
                events
                  .map(encode)
                  .map(c => Base64.getEncoder.encodeToString(c.toArray))
                  .map(data => s.PubsubMessage(data = Some(data)))
              )
            ),
          )
      ).flatMap { res =>
        res.body match {
          case Left(value)  => ZIO.fail(Throwable(value))
          case Right(value) => ZIO.succeed(value.messageIds.getOrElse(Chunk.empty).map(MessageId(_)))
        }
      }
    )

  def topicNameGen(projectId: String): Gen[Any, TopicName] = for {
    topic <- Gen.alphaNumericStringBounded(10, 10).map("topic_" + _)
  } yield TopicName(projectId = projectId, topic = topic)

  val subscriptionNameGen: Gen[Any, SubscriptionName] = for {
    projectId      <- Gen.alphaNumericString
    subscriptionId <- Gen.alphaNumericStringBounded(10, 10).map("sub_" + _)
  } yield SubscriptionName(projectId = projectId, subscription = subscriptionId)

  def topicWithSubscriptionGen(projectId: String): Gen[Any, (TopicName, SubscriptionName)] = for {
    topicName        <- topicNameGen(projectId)
    subscriptionName <- subscriptionNameGen
  } yield (topicName, subscriptionName)

  def someTopicWithSubscriptionName(projectId: String): ZIO[Any, Nothing, (TopicName, SubscriptionName)] =
    topicWithSubscriptionGen(projectId).runHead.map(_.get)

  def findSubscription(
    subscription: SubscriptionName
  ): RIO[Backend[Task], Option[s.Subscription]] = for {
    client <- ZIO.service[Backend[Task]]
    result <-
      client.send(p.Subscriptions.get(projectsId = subscription.projectId, subscriptionsId = subscription.subscription))
  } yield result.body.toOption

  val encodingGen: Gen[Any, Encoding] = Gen.fromIterable(List(Encoding.Binary, Encoding.Json))

  def subscriptionsConfigsGen(topicName: TopicName): Gen[PubsubConnectionConfig.Emulator, Subscription] = (for {
    filter <- Gen
                .option[Any, SubscriberFilter](
                  Gen.mapOf(Gen.alphaNumericString, Gen.alphaNumericString).map(SubscriberFilter.matchingAttributes(_))
                )
    enableOrdering <- Gen.boolean
    expiration     <- Gen.option(Gen.finiteDuration(24.hours, 30.days))
    name           <- subscriptionNameGen
  } yield Subscription(
    topicName = topicName,
    name = name,
    filter = filter,
    enableOrdering = enableOrdering,
    expiration = expiration,
    deadLettersSettings = None,
  ))

  def pull(
    subscription: SubscriptionName,
    returnImmediately: Boolean = false,
    maxMessages: Int = HttpSubscriber.defaults.maxMessagesPerPull,
  ): ZIO[HttpSubscriber, Throwable, Chunk[(ReceivedMessage[Chunk[Byte]], AckReply)]] =
    ZIO.serviceWithZIO[HttpSubscriber](
      _.pull(subscription, returnImmediately = Some(returnImmediately), maxMessages = maxMessages)
    )
}
