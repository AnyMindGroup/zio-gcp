package com.anymindgroup.pubsub

import zio.Random
import zio.test.*

object SerdesSpec extends ZIOSpecDefault {
  override def spec: Spec[Any, Any] = suite("SerdesSpec")(
    test("Serialize/deserialize UTF8 string") {
      check(Gen.int(0, 500)) { strLength =>
        for
          data         <- Live.live(Random.nextString(strLength))
          serialized   <- Serde.utf8String.serialize(data)
          deserialized <- Serde.utf8String.deserialize(ReceivedMessage(data = serialized, meta = null))
          _            <- assertTrue(data == deserialized)
        yield assertCompletes
      }
    },
    test("Serialize/deserialize integer") {
      check(Gen.int) { data =>
        for
          serialized   <- Serde.int.serialize(data)
          deserialized <- Serde.int.deserialize(ReceivedMessage(data = serialized, meta = null))
          _            <- assertTrue(data == deserialized)
        yield assertCompletes
      }
    },
    test("Serialize/deserialize array of bytes") {
      check(Gen.chunkOf(Gen.byte)) { data =>
        for
          serialized   <- Serde.byteArray.serialize(data)
          deserialized <- Serde.byteArray.deserialize(ReceivedMessage(data = serialized, meta = null))
          _            <- assertTrue(data == deserialized)
        yield assertCompletes
      }
    },
  )
}
