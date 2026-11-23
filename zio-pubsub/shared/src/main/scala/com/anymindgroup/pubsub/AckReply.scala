package com.anymindgroup.pubsub

import java.util.concurrent.atomic.AtomicLong

import zio.{Chunk, UIO, ZIO}

opaque type AckId = String
object AckId:
  def apply(v: String): AckId            = v
  extension (a: AckId) def value: String = a

trait AckReply {
  def ack(): UIO[Unit]

  /**
   * Signals that the message could not be processed and should be redelivered.
   * Equivalent to `modifyAckDeadline(0)`.
   */
  def nack(): UIO[Unit] = modifyAckDeadline(0)

  /**
   * Modifies the ack deadline of the message to `seconds` from now.
   *
   * Can be used to extend the lease of messages that take longer to process
   * than the subscription's ack deadline. Can be called repeatedly to keep
   * extending the deadline. Setting the deadline to 0 makes the message
   * immediately available for redelivery (same as [[nack]]).
   *
   * Values are clamped to the range supported by Pub/Sub
   * ([[AckReply.minAckDeadlineSeconds]] to [[AckReply.maxAckDeadlineSeconds]]).
   */
  def modifyAckDeadline(seconds: Int): UIO[Unit]
}

object AckReply:
  val minAckDeadlineSeconds: Int = 0
  val maxAckDeadlineSeconds: Int = 600

  private[pubsub] def clampAckDeadline(seconds: Int): Int =
    math.max(minAckDeadlineSeconds, math.min(maxAckDeadlineSeconds, seconds))

// internal representation of acknowledgement operations queued for sending
private[pubsub] enum AckOp:
  case Ack
  // seqNr reflects the order in which deadline modifications were requested.
  // It's kept when failed operations are re-queued, as those may end up in the queue after newer ones.
  case ModifyDeadline(seconds: Int, seqNr: Long)

private[pubsub] object AckOp:
  private val seqNrCounter = new AtomicLong(0L)

  def modifyDeadline(seconds: Int): UIO[AckOp] =
    ZIO.succeed(ModifyDeadline(AckReply.clampAckDeadline(seconds), seqNrCounter.incrementAndGet()))

  /**
   * Splits operations into ack ids and the most recently requested deadline
   * modification per ack id.
   */
  def partition(ops: Chunk[(String, AckOp)]): (Chunk[String], Map[String, ModifyDeadline]) =
    ops.foldLeft((Chunk.empty[String], Map.empty[String, ModifyDeadline])) {
      case ((ackIds, modifications), (id, Ack))                => (ackIds :+ id, modifications)
      case ((ackIds, modifications), (id, op: ModifyDeadline)) =>
        modifications.get(id) match
          case Some(existing) if existing.seqNr > op.seqNr => (ackIds, modifications)
          case _                                           => (ackIds, modifications.updated(id, op))
    }
