package com.anymindgroup.pubsub

import java.nio.charset.StandardCharsets

import zio.{Chunk, RIO, ZIO}

private[pubsub] trait Serdes {
  val byteArray: Serde[Any, Chunk[Byte]] =
    new Serde[Any, Chunk[Byte]] {
      override final def serialize(value: Chunk[Byte]): RIO[Any, Chunk[Byte]]             = ZIO.succeed(value)
      override final def deserialize(message: ReceivedMessage.Raw): RIO[Any, Chunk[Byte]] =
        ZIO.succeed(message.data)
    }

  val int: Serde[Any, Int] =
    new Serde[Any, Int] {
      override final def serialize(value: Int): RIO[Any, Chunk[Byte]] =
        ZIO.succeed(Chunk.fromArray(BigInt(value).toByteArray))
      override final def deserialize(message: ReceivedMessage.Raw): RIO[Any, Int] =
        ZIO.attempt(BigInt(message.data.toArray).intValue)
    }

  val utf8String: Serde[Any, String] =
    new Serde[Any, String] {
      override final def serialize(value: String): RIO[Any, Chunk[Byte]] =
        ZIO.attempt(Chunk.fromArray(value.getBytes(StandardCharsets.UTF_8)))
      override final def deserialize(message: ReceivedMessage.Raw): RIO[Any, String] =
        ZIO.attempt(String(message.data.toArray, StandardCharsets.UTF_8))
    }
}
