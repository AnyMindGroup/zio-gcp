package com.anymindgroup.pubsub

import zio.stream.ZStream

trait Subscriber {
  def subscribeRaw(subscriptionName: SubscriptionName): ZStream[Any, Throwable, RawReceipt]

  final def subscribe[R, E](
    subscriptionName: SubscriptionName,
    deserializer: Deserializer[R, E],
  ): ZStream[R, Throwable, Receipt[E]] =
    subscribeRaw(subscriptionName).via(Pipeline.deserializerPipeline(deserializer))
}
