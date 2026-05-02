import com.anymindgroup.pubsub.*
import com.anymindgroup.pubsub.google.{createClient, makeStreamingPullSubscriber, makeTopicPublisher}
import com.google.cloud.pubsub.v1.{
  SubscriptionAdminClient,
  SubscriptionAdminSettings,
  TopicAdminClient,
  TopicAdminSettings,
}
import com.google.pubsub.v1.Subscription as GSubscription

import zio.ZIO.logInfo
import zio.{ZIO, ZLayer}

object ZIOPubsubTestGoogle extends ZIOPubsubTestApp with ZIOPubsubTestRuntime:

  private def pubLayer = ZLayer.scoped:
    for
      c         <- ZIO.service[PubsubConnectionConfig]
      topicName <- ZIO.service[TopicName]
      p         <- makeTopicPublisher(
             topicName = topicName,
             serializer = Serde.utf8String,
             connection = c,
           )
    yield p

  private def subLayer = ZLayer.scoped:
    for
      c <- ZIO.service[PubsubConnectionConfig]
      s <- makeStreamingPullSubscriber(connection = c)
    yield s

  override def testBootstrap =
    ZLayer.scoped {
      for
        connection       <- ZIO.service[PubsubConnectionConfig]
        subscriptionName <- ZIO.service[SubscriptionName]
        topicName        <- ZIO.service[TopicName]
        ta               <- createClient(
                TopicAdminSettings.newBuilder(),
                TopicAdminClient.create(_),
                connection,
              )
        _  <- logInfo(s"⏳ Creating topic ${topicName.fullName}...")
        t  <- ZIO.attempt(ta.createTopic(topicName.fullName))
        _  <- logInfo(s"✅ Topic ${t.getName()} created")
        _  <- logInfo(s"⏳ Creating subscription ${subscriptionName.fullName}...")
        sa <- createClient(
                SubscriptionAdminSettings.newBuilder(),
                SubscriptionAdminClient.create(_),
                connection,
              )
        s <- ZIO.attempt(
               sa.createSubscription(
                 GSubscription
                   .newBuilder()
                   .setTopic(topicName.fullName)
                   .setName(subscriptionName.fullName)
                   .build()
               )
             )
        _ <- logInfo(s"✅ Subscription ${s.getName()} created")
      yield ()
    } >>> (pubLayer ++ subLayer)
