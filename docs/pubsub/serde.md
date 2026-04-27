# Serialization / Deserialization

Serializer and deserializer are available via `import com.anymindgroup.pubsub.{Serializer, Deserializer}`.

A `Deserializer` basically represents a function that takes a message with array of bytes as input and returns type `T` by using environment `R`.  
Other meta information under `ReceivedMessage` can be included into building type `T` apart from the message body.  
Defined as follows:
```scala
trait Deserializer[-R, +T] {
  def deserialize(message: ReceivedMessage[Chunk[Byte]]): RIO[R, T]
}
```

`Serializer` works the other way around, given input of type `T`, return an array of bytes using environmet `R`:
```scala
trait Serializer[-R, -T] {
  def serialize(data: T): RIO[R, Chunk[Byte]]
}
```

`Serde` is a combination of both:
```scala
trait Serde[-R, T] extends Serializer[R, T] with Deserializer[R, T] {}
```

The object `com.anymindgroup.pubsub.Serde` contains built-in serializers/deserializers.
Currently available:
 - `Serde.byteArray`
 - `Serde.int`
 - `Serde.utf8String`

## Serializer/Deserializer for custom data types

Example of creating a serializer / deserializer for a data type from/to a JSON string using [zio-json](https://github.com/zio/zio-json):

```scala
import com.anymindgroup.pubsub.*, zio.*, zio.json.*

// given data structure with a json codec like
case class MyData(name: String, age: Int) derives JsonCodec

// a deserializer implementation can look like
val myDataDes: Deserializer[Any, MyData] =
  message =>
    String(message.data).fromJson[MyData] match
      case Left(err)    => ZIO.fail(Throwable(s"Failed to deserialize: $err"))
      case Right(value) => ZIO.succeed(value)

// and a serializer like
val myDataSer: Serializer[Any, MyData] = data => ZIO.succeed(data.toJson.getBytes())
```

## Handling deserialization errors

Example for handling deserialization errors in a subsription process:

```scala
import com.anymindgroup.pubsub.*, zio.*, zio.json.*

case class MyData(name: String, age: Int) derives JsonCodec

// deserializer returning the result as Either instead of failing
val myDataDes: Deserializer[Any, Either[String, MyData]] = message =>
  ZIO.succeed(String(message.data).fromJson[MyData])

// result can be handled in the subscription process e.g. like
def subStream(s: Subscriber) =
  s.subscribe("my_sub_name", myDataDes).mapZIO { (message, reply) =>
    message.data match
      case Left(err)   => reply.nack() *> ZIO.logError(s"Failed to deserialize: $err")
      case Right(data) => reply.ack() *> ZIO.logInfo(s"Data ok $data")
}
```