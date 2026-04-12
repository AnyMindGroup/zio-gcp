package com.anymindgroup.pubsub

import zio.Scope
import zio.test.*
import zio.test.Assertion.equalTo

object SubscriberFilterSpec extends ZIOSpecDefault {

  // https://cloud.google.com/pubsub/docs/subscription-message-filter#filtering_syntax
  override def spec: Spec[TestEnvironment & Scope, Any] = suite("SubscriberFilterSpec")(
    test("create from map") {
      import SubscriberFilter.*

      for
        _ <- assert(matchingAttributes(Map.empty).value)(equalTo(""))
        _ <- assert(matchingAttributes(Map("a" -> "b")).value)(equalTo("""attributes.a="b""""))
        _ <- assert(matchingAttributes(Map("a" -> "b", "c" -> "d")).value)(
               equalTo("""attributes.a="b" AND attributes.c="d"""")
             )
      yield assertCompletes
    }
  )

}
