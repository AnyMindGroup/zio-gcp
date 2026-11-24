package com.anymindgroup.pubsub

import zio.Chunk
import zio.test.*

object AckOpSpec extends ZIOSpecDefault {
  override def spec: Spec[Any, Any] = suite("AckOpSpec")(
    test("coalesce keeps acks and the most recently requested deadline per ack id") {
      val coalesced = AckOp.coalesce(
        Chunk(
          "1" -> AckOp.ModifyDeadline(300, seqNr = 3),
          "2" -> AckOp.ModifyDeadline(30, seqNr = 1),
          "3" -> AckOp.ModifyDeadline(30, seqNr = 2),
          "3" -> AckOp.Ack,
          "1" -> AckOp.ModifyDeadline(30, seqNr = 1), // outdated, e.g. re-queued after a failure
          "4" -> AckOp.Ack,
          "4" -> AckOp.Ack,
        )
      )
      assertTrue(
        coalesced.map(_._1).distinct.size == coalesced.size,
        coalesced.toSet == Set(
          "1" -> AckOp.ModifyDeadline(300, seqNr = 3),
          "2" -> AckOp.ModifyDeadline(30, seqNr = 1),
          "3" -> AckOp.Ack,
          "4" -> AckOp.Ack,
        ),
      )
    }
  )
}
