# Subscriber

## Basic subscription
A basic subscription stream that is going to deserialize message data as integers can be defined as follows:
```scala
import com.anymindgroup.pubsub.*, zio.*, zio.stream.ZStream

def subStream(s: Subscriber): ZStream[Any, Throwable, (ReceivedMessage[Int], AckReply)] =
  s.subscribe(subscriptionName = "basic_example", des = Serde.int)
```

In the next step one can define a function that is going to process a message 
and reply depending on the result:
```scala
import zio.Console.*, zio.ZIO.*

// ack on successful process
// nack and log the error cause on failures
def process(message: ReceivedMessage[Int], reply: AckReply) =
  printLine(s"Processing of $message that can fail...").exit.flatMap {
    case Exit.Success(_)     => reply.ack()
    case Exit.Failure(cause) => reply.nack() *> logErrorCause("Failure log", cause)
  }
```

Processing can be controlled according to the needs.  
E.g. to process all messages in sequence:
```scala
def processStream(s: Subscriber) = subStream(s).mapZIO(process)
```

Process up to 16 messages concurrently:
```scala
def processStream(s: Subscriber) = subStream(s).mapZIOPar(16)(process)
```

Group by ordering key (if present) and process the groups in parallel
while items with same ordering key are processed sequentially:  

```scala
def processStream(s: Subscriber) = subStream(s).groupByKey((m, _) => m.orderingKey.getOrElse(m.messageId).hashCode()) {
  (_, groupStream) => groupStream.mapZIO(process)
}
```

Putting it all together for exection by providing the `Subscriber` implementation:
```scala
object MyProcess extends ZIOAppDefault:
  // execute by providing the subscriber implementation
  def run = for 
    subscriber <- com.anymindgroup.pubsub.http.makeSubscriber()
    _ <- processStream(subscriber).runDrain
  yield ()  
```

## Subscription with custom deserializer

A subscription with a custom deserializer can be defined as follows:

```scala
import com.anymindgroup.pubsub.*, zio.*, zio.stream.ZStream

// deserialize an array of bytes to an UTF8 string
// the output type of the deserializer is also represented by the type: Deserializer[?, String]
val utf8StringDes: Deserializer[Any, String] = (message: ReceivedMessage[Chunk[Byte]]) =>
  ZIO.attempt(String(message.data, java.nio.charset.StandardCharsets.UTF_8))

// deserializer output will be reflected in the message type that is emitted by the stream: ReceivedMessage[String]
def subStream(s: Subscriber): ZStream[Any, Throwable, (ReceivedMessage[String], AckReply)] =
  s.subscribe(subscriptionName = "basic_example", des = utf8StringDes)
```

For custom data types see the [Serializer/Deserializer for custom data types](./serde.md#serializerdeserializer-for-custom-data-types) section.

## Subscription without deserializer

It's also possible to create a stream without a deserializer which is going 
to emit `ReceivedMessage[Chunk[Byte]]` containing data as array of bytes.

```scala
import com.anymindgroup.pubsub.*, zio.stream.ZStream

def subStream(s: Subscriber): ZStream[Any, Throwable, (ReceivedMessage[Chunk[Byte]], AckReply)] =
  s.subscribeRaw(subscriptionName = "basic_example")
```

## Handling deserialization errors
See section [handling deserialization errors](./serde.md#handling-deserialization-errors).