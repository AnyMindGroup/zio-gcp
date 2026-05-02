package com.anymindgroup.pubsub
package google

import scala.jdk.CollectionConverters.*

import com.google.cloud.pubsub.v1.stub.{GrpcPublisherStub, PublisherStubSettings}
import com.google.protobuf.ByteString
import com.google.pubsub.v1.{PublishRequest, PubsubMessage as GPubsubMessage}

import zio.{Chunk, NonEmptyChunk, RIO, Scope, ZIO}

class GoogleTopicPublisher[R, E] private[pubsub] (
  publisher: GrpcPublisherStub,
  topicName: TopicName,
  serde: Serializer[R, E],
) extends Publisher[R, E] {
  override def publish(messages: NonEmptyChunk[PublishMessage[E]]): RIO[R, NonEmptyChunk[MessageId]] =
    for {
      gMessages <- ZIO.foreach(messages)(toPubsubMessage(_)).map(_.toChunk.asJava)
      response  <- ZIO.fromFutureJava(
                    publisher
                      .publishCallable()
                      .futureCall(
                        PublishRequest
                          .newBuilder()
                          .setTopic(topicName.fullName)
                          .addAllMessages(gMessages)
                          .build()
                      )
                  )
      ids <- NonEmptyChunk.fromChunk(Chunk.fromJavaIterable(response.getMessageIdsList()).map(MessageId(_))) match
               case None        => ZIO.dieMessage("Unexpected response with no message ids")
               case Some(value) => ZIO.succeed(value)
    } yield ids

  override def publish(event: PublishMessage[E]): ZIO[R, Throwable, MessageId] =
    publish(NonEmptyChunk(event)).map(_.head)

  private def toPubsubMessage(e: PublishMessage[E]): ZIO[R, Throwable, GPubsubMessage] =
    serde
      .serialize(e.data)
      .map: serializedData =>
        val builder = GPubsubMessage.newBuilder
          .putAllAttributes(e.attributes.asJava)
          .setData(ByteString.copyFrom(serializedData.toArray))

        e.orderingKey match
          case None    => builder.build()
          case Some(o) => builder.setOrderingKey(o.value).build()
}

object GoogleTopicPublisher:
  def make[R, E](
    topicName: TopicName,
    serializer: Serializer[R, E],
    connection: PubsubConnectionConfig,
  ): RIO[Scope, GoogleTopicPublisher[R, E]] =
    createStub(
      connection = connection,
      builder = PublisherStubSettings.newBuilder(),
      create = GrpcPublisherStub.create,
    ).map(GoogleTopicPublisher(_, topicName, serializer))
