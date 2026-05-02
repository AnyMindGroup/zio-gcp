package com.anymindgroup.pubsub

import java.time.Instant

import zio.Chunk

final case class ReceivedMessage[T](meta: ReceivedMessage.Metadata, data: T) {
  def orderingKey: Option[OrderingKey] = meta.orderingKey
  def messageId: MessageId             = meta.messageId
  def ackId: AckId                     = meta.ackId
  def publishTime: Instant             = meta.publishTime
  def attributes: Map[String, String]  = meta.attributes
  def deliveryAttempt: Int             = meta.deliveryAttempt
}

object ReceivedMessage {
  type Raw = ReceivedMessage[Chunk[Byte]]

  final case class Metadata(
    messageId: MessageId,
    ackId: AckId,
    publishTime: Instant,
    orderingKey: Option[OrderingKey],
    attributes: Map[String, String],
    deliveryAttempt: Int,
  )

}
