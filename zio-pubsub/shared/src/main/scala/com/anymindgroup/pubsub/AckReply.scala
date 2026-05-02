package com.anymindgroup.pubsub

import zio.UIO

opaque type AckId = String
object AckId:
  def apply(v: String): AckId            = v
  extension (a: AckId) def value: String = a

trait AckReply {
  def ack(): UIO[Unit]
  def nack(): UIO[Unit]
}
