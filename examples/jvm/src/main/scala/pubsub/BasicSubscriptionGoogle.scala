package pubsub
import zio.*
import zio.ZIO.*

import com.anymindgroup.pubsub.*
import google.*

object BasicSubscriptionGoogle extends ZIOAppDefault:
  def run = makeStreamingPullSubscriber(
    // set by default to "PubsubConnectionConfig.Cloud" when not running against an emulator
    connection = PubsubConnectionConfig.Emulator("localhost", 8085)
  ).flatMap:
    _.subscribe(
      subscriptionName = SubscriptionName("gcp_project", "subscription"),
      deserializer = Serde.utf8String,
    ).mapZIO { (message, ackReply) =>
      for
        _ <- logInfo(
               s"Received message" +
                 s" with id ${message.messageId.value}" +
                 s" and data ${message.data}"
             )
        _ <- ackReply.ack()
      yield ()
    }.runDrain
