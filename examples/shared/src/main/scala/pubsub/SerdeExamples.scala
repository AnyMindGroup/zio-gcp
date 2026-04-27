package pubsub
import com.anymindgroup.pubsub.*, zio.*, zio.json.*

object CustomDataTypeSerdeExampleA:
  // given data structure with a json codec like
  case class MyData(name: String, age: Int) derives JsonCodec

  // define a custom deserializer
  val myDataDes: Deserializer[Any, MyData] =
    message =>
      String(message.data.toArray).fromJson[MyData] match
        case Left(err)    => ZIO.fail(Throwable(s"Failed to deserialize: $err"))
        case Right(value) => ZIO.succeed(value)

  // define a custom serializer
  val myDataSerializer: Serializer[Any, MyData] = data => ZIO.succeed(Chunk.fromArray(data.toJson.getBytes()))

// example with deserialization error handling
object CustomDataTypeSerdeExampleB:
  case class MyData(name: String, age: Int) derives JsonCodec

  // deserializer returning the result as Either instead of failing
  val myDataDes: Deserializer[Any, Either[String, MyData]] = message =>
    ZIO.succeed(String(message.data.toArray).fromJson[MyData])

  // result can be handled in the subscription process e.g. like
  def subStream(s: Subscriber) =
    s.subscribe(SubscriptionName("any", "my_sub_name"), myDataDes)
      .mapZIO: (message, reply) =>
        message.data match
          case Left(err)   => reply.nack() *> ZIO.logError(s"Failed to deserialize: $err")
          case Right(data) => reply.ack() *> ZIO.logInfo(s"Data ok $data")
