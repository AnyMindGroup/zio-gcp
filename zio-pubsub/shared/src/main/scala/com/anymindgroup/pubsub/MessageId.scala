package com.anymindgroup.pubsub

opaque type MessageId = String
object MessageId:
  def apply(value: String): MessageId        = value
  extension (a: MessageId) def value: String = a
