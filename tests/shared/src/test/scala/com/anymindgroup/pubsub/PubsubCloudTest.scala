package com.anymindgroup.pubsub
package http

import com.anymindgroup.gcp.pubsub.v1.*

import zio.*
import zio.Console.printLine

// Test for manual execution to run against an actual Pub/Sub in the cloud
//
// Executing the steps:
// 1. create topic
// 2. creeate subscription
// 3. publishe message
// 4. pull and ack message
// 5. delete subscription
// 6. delete topic
//
// if any of the steps fail ensure to clean up manually after executed steps if needed
//
// To execute via sbt
// 1. Ensure GCP_TEST_PROJECT / GCP_TEST_PUBSUB_TOPIC / GCP_TEST_PUBSUB_SUB are set in the environment
// 2. Ensure default application credentials are setup with access to the project
// 3. Run: sbt 'zioPubsubTestJVM/Test/runMain com.anymindgroup.pubsub.http.PubsubCloudTest'
object PubsubCloudTest extends ZIOAppDefault:
  def run =
    for
      (topic, sub) <- ZIO.fromEither:
                        for
                          project <- sys.env.get("GCP_TEST_PROJECT").toRight("Missing GCP_TEST_PROJECT")
                          topic   <- sys.env.get("GCP_TEST_PUBSUB_TOPIC").toRight("Missing GCP_TEST_PUBSUB_TOPIC")
                          sub     <- sys.env.get("GCP_TEST_PUBSUB_SUB").toRight("Missing GCP_TEST_PUBSUB_SUB")
                        yield (
                          TopicName(projectId = project, topic = topic),
                          SubscriptionName(projectId = project, subscription = sub),
                        )
      _ <- makeAuthedBackend().flatMap: backend =>
             for
               _ <- printLine(s"⏳ Creating topic ${topic.fullName}...")
               t <- resources.projects.Topics
                      .create(
                        projectsId = topic.projectId,
                        topicsId = topic.topic,
                        request = schemas.Topic(name = topic.fullName),
                      )
                      .send(backend)
                      .flatMap(r => ZIO.fromEither(r.body))
               _ <- printLine(s"✅ Topic ${t.name} created")
               _ <- printLine(s"⏳ Creating subscription ${sub.fullName}...")
               s <- resources.projects.Subscriptions
                      .create(
                        projectsId = sub.projectId,
                        subscriptionsId = sub.subscription,
                        request = schemas.Subscription(
                          name = sub.fullName,
                          topic = topic.fullName,
                        ),
                      )
                      .send(backend)
                      .flatMap(r => ZIO.fromEither(r.body))
               _   <- printLine(s"✅ Subscription ${s.name} created")
               mId <- ZIO.scoped:
                        makeTopicPublisher(
                          topicName = topic,
                          serializer = Serde.utf8String,
                          backend = Some(backend),
                        ).flatMap:
                          _.publish(PublishMessage(data = "test data", orderingKey = None, attributes = Map.empty))
               _ <- printLine(s"✅ Message with id ${mId.value} published")
               _ <- ZIO.scoped:
                      makeSubscriber(backend = Some(backend)).flatMap:
                        _.subscribe(subscriptionName = sub, deserializer = Serde.utf8String)
                          .mapZIO((m, a) => a.ack() *> printLine(s"✅ Acked message with id ${m.messageId.value}"))
                          .take(1)
                          .runDrain
               _ <- printLine(s"⏳ Deleting subscription ${sub.fullName}...")
               _ <- resources.projects.Subscriptions
                      .delete(
                        projectsId = sub.projectId,
                        subscriptionsId = sub.subscription,
                      )
                      .send(backend)
                      .flatMap(r => ZIO.fromEither(r.body))
               _ <- printLine(s"✅ Subscription ${sub.fullName} deleted")
               _ <- printLine(s"⏳ Deleting topic ${topic.fullName}...")
               _ <- resources.projects.Topics
                      .delete(
                        projectsId = topic.projectId,
                        topicsId = topic.topic,
                      )
                      .send(backend)
                      .flatMap(r => ZIO.fromEither(r.body))
               _ <- printLine(s"✅ Topic deleted ${topic.fullName}")
             yield ()
      _ <- printLine(s"🎉 Test successful! 🎉")
    yield ()
