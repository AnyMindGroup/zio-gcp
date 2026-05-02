package pubsub
import com.anymindgroup.pubsub.*, google.*
import zio.stream.*, zio.*, zio.ZIO.*

object SamplesPublisherGoogle extends ZIOAppDefault:
  def run = makeTopicPublisher(
    topicName = TopicName("gcp_project", "topic"),
    serializer = Serde.utf8String,
    // set by default to "PubsubConnectionConfig.Cloud" when not running against an emulator
    connection = PubsubConnectionConfig.Emulator("localhost", 8085),
  ).flatMap: publisher =>
    ZStream
      .repeatZIOWithSchedule(Random.nextInt.map(i => s"some data $i"), Schedule.fixed(2.seconds))
      .mapZIO { sample =>
        for {
          mId <- publisher.publish(
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
