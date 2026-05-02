package pubsub
import com.anymindgroup.pubsub.*, zio.stream.*, zio.*, zio.ZIO.*

object SamplesPublisher extends ZIOAppDefault:
  def run =
    // run samples publishing given Publisher implementation
    def samplesPublish(p: Publisher[Any, String]) =
      ZStream
        .repeatZIOWithSchedule(Random.nextInt.map(i => s"some data $i"), Schedule.fixed(2.seconds))
        .mapZIO { sample =>
          for {
            mId <- p.publish(
                     PublishMessage(
                       data = sample,
                       attributes = Map.empty,
                       orderingKey = None,
                     )
                   )
            _ <- logInfo(s"Published data $sample with message id ${mId.value}")
          } yield ()
        }
        .runDrain

    val makePublisher: RIO[Scope, Publisher[Any, String]] =
      // make http based topic publisher
      http.makeTopicPublisher(
        topicName = TopicName("gcp_project", "topic"),
        serializer = Serde.utf8String,
        // set by default to "PubsubConnectionConfig.Cloud" when not running against an emulator
        connection = PubsubConnectionConfig.Emulator("localhost", 8085),
      )
      // or similarly by using gRCP based implementation via Google's Java client:
      // google.makeTopicPublisher(
      //   topicName = TopicName("gcp_project", "topic"),
      //   serializer = Serde.utf8String,
      //   connection = PubsubConnectionConfig.Emulator("localhost", 8085),
      // )

    makePublisher.flatMap(samplesPublish)
