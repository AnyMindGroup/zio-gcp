package com.anymindgroup.pubsub
package http

import zio.test.*
import zio.{Chunk, Promise, Ref, Schedule, Scope, ZIO}

import com.anymindgroup.gcp.auth.{AuthedBackend, TokenProvider}
import com.anymindgroup.gcp.pubsub.v1.schemas.ModifyAckDeadlineRequest
import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import sttp.client4.StringBody
import sttp.client4.testing.ResponseStub
import sttp.model.StatusCode

object HttpPubsubSpec extends ZIOSpecDefault {
  override def spec: Spec[Scope, Any] =
    suite("HttpPubsubSpec")(
      PubsubIntegrationSpec.spec(
        publisherImpl = (connection, topic) =>
          makeTopicPublisher(connection = connection, topicName = topic, serializer = Serde.utf8String),
        subscriberImpl = connection => makeSubscriber(connection = connection),
      ),
      suite("HttpSubscriberSpec")(
        List(true, false).map: ack =>
          test(s"failed ${if ack then "ack" else "nack"} requests are retried by provided schedule") {
            ZIO.scoped:
              for
                ackCounter <- Ref.make(0)
                backend    <- backendStub(ackCounter)
                maxRetries  = 5
                subscriber <- makeSubscriber(
                                backend = Some(backend),
                                retrySchedule = Schedule.recurs(maxRetries),
                              )
                exit <- subscriber
                          .subscribe(SubscriptionName("any", "any"), Serde.utf8String)
                          .mapZIO((_, reply) => if ack then reply.ack() else reply.nack())
                          .runDrain
                          .exit
                _ <- assertTrue(exit.isFailure)
                _ <- ackCounter.get.map(retries => assertTrue(retries == maxRetries))
              yield assertCompletes
          }
      ),
      test("only the most recently requested deadline per ack id is sent when batching ack operations") {
        val (ackBatches, modifyDeadlines) = HttpSubscriber.batchAckOps(
          Chunk(
            "1" -> AckOp.ModifyDeadline(30, seqNr = 1),
            "2" -> AckOp.ModifyDeadline(30, seqNr = 2),
            "3" -> AckOp.Ack,
            "1" -> AckOp.ModifyDeadline(300, seqNr = 3),
            "2" -> AckOp.ModifyDeadline(0, seqNr = 4),
            "1" -> AckOp.ModifyDeadline(45, seqNr = 5),
          )
        )
        val sentDeadlines = modifyDeadlines.flatMap((seconds, ops) => ops.map((id, _) => id -> seconds))
        assertTrue(
          ackBatches.map(_.toList) == List(List("3")),
          sentDeadlines.size == 2,
          sentDeadlines.toMap == Map("1" -> 45, "2" -> 0),
        )
      },
      test("re-queued failed deadline modification does not override a newer one") {
        // 300s extension was queued while the earlier 30s request failed and got re-queued after it
        val (_, modifyDeadlines) = HttpSubscriber.batchAckOps(
          Chunk(
            "1" -> AckOp.ModifyDeadline(300, seqNr = 2),
            "1" -> AckOp.ModifyDeadline(30, seqNr = 1),
          )
        )
        assertTrue(modifyDeadlines.map((seconds, ops) => (seconds, ops.map(_._1).toList)) == List((300, List("1"))))
      },
      test("ack operations exceeding the request limit are split into requests containing each ack id once") {
        val ids = (1 to 3000).map(_.toString)
        val ops = Chunk.fromIterable(
          // newer modifications first, then outdated modifications (e.g. re-queued after a failure), then acks
          ids.take(2000).map(id => id -> AckOp.ModifyDeadline(300, seqNr = 10_000L + id.toLong)) ++
            ids.take(2000).map(id => id -> AckOp.ModifyDeadline(30, seqNr = id.toLong)) ++
            ids.drop(1500).map(id => id -> AckOp.Ack)
        )
        val (ackBatches, modifyDeadlines) = HttpSubscriber.batchAckOps(ops)
        val ackedIds                      = ackBatches.flatMap(_.toList)
        val modifiedIds                   = modifyDeadlines.flatMap((_, ops) => ops.map(_._1))
        assertTrue(
          ackBatches.forall(_.size <= AckOp.maxAckIdsPerRequest),
          modifyDeadlines.forall(_._2.size <= AckOp.maxAckIdsPerRequest),
          ackedIds.sorted == ids.drop(1500).toList.sorted,
          // modifications of acked ids are dropped
          modifiedIds.sorted == ids.take(1500).toList.sorted,
          modifyDeadlines.map(_._1).distinct == List(300),
        )
      },
      test("deadline modifications get clamped deadlines and increasing sequence numbers") {
        for ops <- ZIO.foreach(List(30, 300, 1000))(AckOp.modifyDeadline)
        yield
          val seqNrs = ops.collect { case AckOp.ModifyDeadline(_, seqNr) => seqNr }
          assertTrue(
            ops.collect { case AckOp.ModifyDeadline(seconds, _) => seconds } == List(30, 300, 600),
            seqNrs == seqNrs.sorted,
            seqNrs.distinct.size == 3,
          )
      },
      test("modifyAckDeadline sends modifyAckDeadline requests with clamped deadline per ack id") {
        val deadlineByAckId = Map("1" -> 30, "2" -> 30, "3" -> 45, "4" -> 1000)
        ZIO.scoped:
          for
            recorded   <- Ref.make(Map.empty[String, Int])
            done       <- Promise.make[Nothing, Unit]
            backend    <- modifyDeadlineBackendStub(deadlineByAckId.keys.toList, recorded, done)
            subscriber <- makeSubscriber(backend = Some(backend))
            _          <- subscriber
                   .subscribe(SubscriptionName("any", "any"), Serde.utf8String)
                   .mapZIO((msg, reply) => reply.modifyAckDeadline(deadlineByAckId(msg.meta.ackId.value)))
                   .runDrain
                   .race(done.await)
            res <- recorded.get
          yield assertTrue(res == deadlineByAckId.updated("4", 600))
      },
    ) @@ TestAspect.native(TestAspect.sequential)

  private def modifyDeadlineBackendStub(
    ackIds: List[String],
    recorded: Ref[Map[String, Int]],
    done: Promise[Nothing, Unit],
  ) =
    Ref
      .make(0)
      .map: pullCount =>
        AuthedBackend(
          TokenProvider.noTokenProvider,
          platformStub
            .whenRequestMatches(r => r.uri.pathToString.endsWith(":pull"))
            .thenRespondF {
              pullCount.getAndIncrement.flatMap:
                case 0 =>
                  ZIO.succeed(
                    ResponseStub.adjust(
                      ackIds
                        .map(id =>
                          s"""{"ackId":"$id","message":{"data":"","messageId":"$id","publishTime":"2025-01-01T00:00:00Z"}}"""
                        )
                        .mkString("""{"receivedMessages":[""", ",", "]}"),
                      StatusCode.Ok,
                    )
                  )
                case _ => ZIO.never
            }
            .whenRequestMatches(r => r.uri.pathToString.endsWith(":modifyAckDeadline"))
            .thenRespondF { r =>
              val req = r.body match
                case StringBody(b, _, _) => readFromString[ModifyAckDeadlineRequest](b)
                case other               => throw new Exception(s"Unexpected body $other")
              recorded
                .updateAndGet(_ ++ req.ackIds.map(_ -> req.ackDeadlineSeconds))
                .flatMap(m => ZIO.when(m.size == ackIds.size)(done.succeed(())))
                .as(ResponseStub.adjust("{}", StatusCode.Ok))
            }
            .whenAnyRequest
            .thenRespondNotFound(),
        )

  private def backendStub(ackCounter: Ref[Int]) =
    Ref
      .make(0)
      .map: pullCount =>
        AuthedBackend(
          TokenProvider.noTokenProvider,
          platformStub
            .whenRequestMatches(r => r.uri.pathToString.endsWith(":pull"))
            .thenRespondF {
              // return a message on first pull only
              pullCount.get.flatMap:
                case 0 =>
                  pullCount.getAndIncrement.as(
                    ResponseStub.adjust(
                      """|{
                         |  "receivedMessages":[
                         |    {
                         |      "ackId": "1",
                         |      "message": {
                         |        "data": "",
                         |        "messageId": "1",
                         |        "publishTime": "2025-01-01T00:00:00Z"
                         |      }
                         |    }
                         |  ]
                         |}""".stripMargin,
                      StatusCode.Ok,
                    )
                  )
                case _ => ZIO.never
            }
            .whenRequestMatches(r => r.uri.pathToString.endsWith(":acknowledge"))
            // return failure on any acknowledge request
            .thenRespondF(ackCounter.getAndIncrement.as(ResponseStub.adjust("", StatusCode(400))))
            .whenAnyRequest
            .thenRespondNotFound(),
        )
}
