package pubsub
import com.anymindgroup.pubsub.*, zio.*, zio.stream.ZStream

object BasicSubscription extends ZIOAppDefault:
  def run =
    // create a subscription stream based on Subscriber implementation provided
    def subStream(s: Subscriber): ZStream[Any, Throwable, Unit] =
      s.subscribe(
        subscriptionName = SubscriptionName("gcp_project", "subscription"),
        deserializer = Serde.utf8String,
      ).mapZIO: (message, ackReply) =>
        for
          _ <- ZIO.logInfo(
                 s"Received message" +
                   s" with id ${message.messageId.value}" +
                   s" and data ${message.data}"
               )
          _ <- ackReply.ack()
        yield ()

    val makeSubscriber: RIO[Scope, Subscriber] =
      // make http based Subscriber implementation
      http.makeSubscriber(
        // set by default to "PubsubConnectionConfig.Cloud" when not running against an emulator
        connection = PubsubConnectionConfig.Emulator("localhost", 8085)
      )
      // or similarly by using gRCP/StreamingPull API based implementation via Google's Java client:
      // google.makeStreamingPullSubscriber(
      //  connection = PubsubConnectionConfig.Emulator("localhost", 8085)
      // )

    makeSubscriber.flatMap(subStream(_).runDrain)
