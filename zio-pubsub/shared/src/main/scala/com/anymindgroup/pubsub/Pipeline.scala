package com.anymindgroup.pubsub

import zio.stream.ZPipeline
import zio.{RIO, ZIO}

object Pipeline {

  private def decodedPipeline[R, E, B](
    f: Receipt[E] => RIO[R, B]
  ): ZPipeline[R, Throwable, Receipt[E], B] =
    ZPipeline.mapZIO[R, Throwable, Receipt[E], B](f)

  def processPipeline[R, E, T](
    process: ReceivedMessage[E] => RIO[R, T]
  ): ZPipeline[R, Throwable, Receipt[E], T] =
    decodedPipeline[R, E, T] { case (event, ackReply) =>
      process(event)
        .tapErrorCause(c => ZIO.logErrorCause("Error on processing event", c))
        .tap(_ => ackReply.ack())
    }

  def autoAckPipeline[E]: ZPipeline[Any, Throwable, (E, AckReply), E] =
    ZPipeline.mapZIO[Any, Throwable, (E, AckReply), E] { case (event, ackReply) =>
      ackReply.ack().as(event)
    }

  def deserializerPipeline[R, T](deserializer: Deserializer[R, T]): ZPipeline[R, Throwable, RawReceipt, Receipt[T]] =
    ZPipeline.mapZIO { case (receivedMessage, ackReply) =>
      deserializer
        .deserialize(receivedMessage)
        .map(r => (ReceivedMessage(receivedMessage.meta, r), ackReply))
    }
}
