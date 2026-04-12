package com.anymindgroup.pubsub
package google

import com.google.cloud.pubsub.v1.{
  SubscriptionAdminClient,
  SubscriptionAdminSettings,
  TopicAdminClient,
  TopicAdminSettings,
}
import com.google.pubsub.v1.Subscription as GSubscription

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
// 3. Run: sbt 'zioPubsubGoogle/Test/runMain com.anymindgroup.pubsub.google.PubsubCloudTest'
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
      _  <- printLine(s"⏳ Creating topic ${topic.fullName}...")
      ta <- createClient(TopicAdminSettings.newBuilder(), TopicAdminClient.create(_))
      t  <- ZIO.attempt(ta.createTopic(topic.fullName))
      _  <- printLine(s"✅ Topic ${t.getName()} created")
      _  <- printLine(s"⏳ Creating subscription ${sub.fullName}...")
      sa <- createClient(SubscriptionAdminSettings.newBuilder(), SubscriptionAdminClient.create(_))
      s  <- ZIO.attempt(
             sa.createSubscription(
               GSubscription
                 .newBuilder()
                 .setTopic(topic.fullName)
                 .setName(sub.fullName)
                 .build()
             )
           )
      _   <- printLine(s"✅ Subscription ${s.getName()} created")
      mId <- ZIO.scoped:
               makeTopicPublisher(
                 topicName = topic,
                 serializer = Serde.utf8String,
               ).flatMap:
                 _.publish(PublishMessage(data = "test data", orderingKey = None, attributes = Map.empty))
      _ <- printLine(s"✅ Message with id ${mId.value} published")
      _ <- ZIO.scoped:
             makeStreamingPullSubscriber().flatMap:
               _.subscribe(subscriptionName = sub, deserializer = Serde.utf8String)
                 .mapZIO((m, a) => a.ack() *> printLine(s"✅ Acked message with id ${m.messageId.value}"))
                 .take(1)
                 .runDrain
      _ <- printLine(s"⏳ Deleting subscription ${sub.fullName}...")
      _ <- ZIO.attempt(sa.deleteSubscription(sub.fullName))
      _ <- printLine(s"✅ Subscription ${sub.fullName} deleted")
      _ <- printLine(s"⏳ Deleting topic ${topic.fullName}...")
      _ <- ZIO.attempt(ta.deleteTopic(topic.fullName))
      _ <- printLine(s"✅ Topic deleted ${topic.fullName}")
      _ <- printLine(s"🎉 Test successful! 🎉")
    yield ()
