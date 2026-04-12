# Admin

Example to setup topics and subscriptions with dead letter settings via the admin client:  

```scala
import com.anymindgroup.pubsub.google.{PubsubAdmin, PubsubConnectionConfig}
import com.anymindgroup.pubsub.*
import zio.*

object ExamplesAdminSetup extends ZIOAppDefault:
  def run: Task[Unit] = PubsubAdmin.setup(
    connection = PubsubConnectionConfig.Emulator(
      PubsubConnectionConfig.GcpProject("any"),
      "localhost:8085",
    ),
    topics = List(exampleTopic, exampleDeadLettersTopic),
    subscriptions = List(exampleSub, exampleDeadLettersSub),
  )

  val exampleTopic: Topic[Any, Int] = Topic(
    name = "basic_example",
    schemaSetting = SchemaSettings(
      encoding = Encoding.Binary,
      schema = None,
    ),
    serde = Serde.int,
  )

  val exampleDeadLettersTopic: Topic[Any, Int] =
    exampleTopic.copy(name = s"${exampleTopic.name}__dead_letters")

  val exampleSub: Subscription = Subscription(
    topicName = exampleTopic.name,
    name = "basic_example",
    filter = None,
    enableOrdering = false,
    expiration = None,
    deadLettersSettings = Some(DeadLettersSettings(exampleDeadLettersTopic.name, 5)),
  )

  val exampleDeadLettersSub: Subscription = exampleSub.copy(
    topicName = exampleDeadLettersTopic.name,
    name = s"${exampleSub.name}__dead_letters",
    deadLettersSettings = None,
  )
```