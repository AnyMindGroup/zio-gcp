package com.anymindgroup.pubsub.google

import com.anymindgroup.pubsub.Serde

import zio.Scope
import zio.test.*

object GooglePubsubSpec extends ZIOSpecDefault {
  override def spec: Spec[Scope, Any] = suite("GooglePubsubSpec")(
    com.anymindgroup.pubsub.PubsubIntegrationSpec.spec(
      publisherImpl = (connection, topic) =>
        makeTopicPublisher(
          topicName = topic,
          serializer = Serde.utf8String,
          connection = connection,
        ),
      subscriberImpl = connection => makeStreamingPullSubscriber(connection = connection),
    )
  )
}
