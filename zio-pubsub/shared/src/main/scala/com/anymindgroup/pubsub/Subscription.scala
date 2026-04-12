package com.anymindgroup.pubsub

import zio.Duration

final case class Subscription(
  topicName: TopicName,
  name: SubscriptionName,
  filter: Option[SubscriberFilter],
  enableOrdering: Boolean,
  expiration: Option[Duration],
  deadLettersSettings: Option[DeadLettersSettings], // Should be left None for subscription to dead letter topic
)

final case class DeadLettersSettings(
  deadLetterTopicName: TopicName,
  maxRetryNum: Int,
)
