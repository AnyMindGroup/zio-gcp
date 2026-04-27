package com.anymindgroup.pubsub

final case class PublishMessage[E](data: E, orderingKey: Option[OrderingKey], attributes: Map[String, String])
