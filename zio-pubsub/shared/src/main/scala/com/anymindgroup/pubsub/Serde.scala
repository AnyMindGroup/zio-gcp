package com.anymindgroup.pubsub

import zio.{Chunk, RIO}

trait Deserializer[-R, +T] {
  def deserialize(message: ReceivedMessage[Chunk[Byte]]): RIO[R, T]
}

trait Serializer[-R, -T] {
  def serialize(data: T): RIO[R, Chunk[Byte]]
}

trait Serde[-R, T] extends Serializer[R, T] with Deserializer[R, T] {}

object Serde extends Serdes {}
