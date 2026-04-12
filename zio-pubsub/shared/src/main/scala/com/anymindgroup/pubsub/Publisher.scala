package com.anymindgroup.pubsub

import zio.{NonEmptyChunk, RIO}

trait Publisher[R, E] {
  def publish(message: PublishMessage[E]): RIO[R, MessageId]

  def publish(message: NonEmptyChunk[PublishMessage[E]]): RIO[R, NonEmptyChunk[MessageId]]
}
