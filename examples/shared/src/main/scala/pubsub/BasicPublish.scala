package pubsub
import zio.*
import zio.ZIO.*

import com.anymindgroup.pubsub.*
import http.*

object BasicPublish extends ZIOAppDefault:
  def run =
    for
      publisher <- makeTopicPublisher(
                     topicName = TopicName("gcp_project", "topic"),
                     serializer = Serde.utf8String,
                     // set by default to "PubsubConnectionConfig.Cloud" when not running against an emulator
                     connection = PubsubConnectionConfig.Emulator("localhost", 8085),
                   )
      data <- Random.nextInt.map(i => s"some data $i")
      mId  <- publisher.publish(
               PublishMessage(
                 data = data,
                 attributes = Map.empty,
                 orderingKey = None,
               )
             )
      _ <- logInfo(s"Published message with id ${mId.value} and data '$data'")
    yield ()
