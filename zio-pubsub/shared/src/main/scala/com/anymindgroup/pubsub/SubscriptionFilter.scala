package com.anymindgroup.pubsub

opaque type SubscriberFilter = String

object SubscriberFilter:
  // TODO extend accoding to specs: https://cloud.google.com/pubsub/docs/subscription-message-filter#filtering_syntax
  def matchingAttributes(values: Map[String, String]): SubscriberFilter =
    values.toList.map { case (k, v) => s"""attributes.$k="$v"""" }.mkString("", " AND ", "")

  def of(value: String): SubscriberFilter = value

  extension (x: SubscriberFilter) def value: String = x
