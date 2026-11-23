package com.anymindgroup.pubsub

import zio.UIO

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
  case ModifyDeadline(seconds: Int)
