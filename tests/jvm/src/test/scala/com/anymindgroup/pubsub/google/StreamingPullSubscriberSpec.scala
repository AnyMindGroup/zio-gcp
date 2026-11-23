package com.anymindgroup.pubsub.google

import java.util.concurrent.atomic.AtomicReference
import java.util as ju

import scala.jdk.CollectionConverters.*

import zio.test.Assertion.*
import zio.test.{
  Gen,
  Live,
  Spec,
  TestAspect,
  TestEnvironment,
  ZIOSpecDefault,
  assert,
  assertCompletes,
  assertTrue,
  assertZIO,
  check,
}
import zio.{Chunk, Promise, Queue, Random, Ref, Schedule, Scope, ZIO, durationInt}

import com.anymindgroup.pubsub.AckOp
import com.google.pubsub.v1.{ReceivedMessage, StreamingPullRequest, StreamingPullResponse}
object StreamingPullSubscriberSpec extends ZIOSpecDefault {

  trait TestBidiStream[A, B] extends BidiStream[A, B] {
    override def send(request: A): Unit                 = ()
    override def closeSendWithError(t: Throwable): Unit = ()
    override def closeSend(): Unit                      = ()
    override def isSendReady(): Boolean                 = true
    override def cancel(): Unit                         = ()
  }

  override def spec: Spec[TestEnvironment & Scope, Any] = suite("StreamingPullSubscriberSpec")(
    test("stream recovers on pull error") {
      val failUntilAttempt = 3

      def initStream(initCountRef: Ref[Int], ackedRef: AtomicReference[Vector[String]]) =
        initCountRef.get
          .map(initCount => testBidiStream(failPull = initCount < (failUntilAttempt - 1), ackedRef = ackedRef))
          .tap(_ => initCountRef.updateAndGet(_ + 1))

      for
        initCountRef <- Ref.make(0)
        processedRef <- Ref.make(0)
        ackedRef      = new AtomicReference(Vector.empty[String])
        ackQueue     <- Queue.unbounded[(String, AckOp)]
        _            <- StreamingPullSubscriber
               .makeStream(
                 initStream(initCountRef, ackedRef),
                 ackQueue,
                 Schedule.recurs(failUntilAttempt + 1),
               )
               .mapZIO(e => processedRef.getAndUpdate(_ + 1) *> e._2.ack())
               .takeUntilZIO(_ => processedRef.get.map(_ > 0)) // take until first successfull processed
               .runCollect
        initCount      <- initCountRef.get
        processedCount <- processedRef.get
        ackedCount      = ackedRef.get().length
        _              <- assertTrue(processedCount == ackedCount)
      yield assertTrue(initCount == failUntilAttempt)
    },
    test("stream recovers on ack error") {
      val failUntilAttempt = 3

      def initStream(initCountRef: Ref[Int], ackedRef: AtomicReference[Vector[String]]) =
        initCountRef.get
          .map(t => testBidiStream(failSend = t < (failUntilAttempt - 1), ackedRef = ackedRef))
          .tap(_ => initCountRef.updateAndGet(_ + 1))

      for
        initCountRef <- Ref.make(0)
        processedRef <- Ref.make(0)
        ackedRef      = new AtomicReference(Vector.empty[String])
        ackQueue     <- Queue.unbounded[(String, AckOp)]
        _            <- StreamingPullSubscriber
               .makeStream(initStream(initCountRef, ackedRef), ackQueue, Schedule.recurs(5))
               .mapZIO(e => processedRef.getAndUpdate(_ + 1) *> e._2.ack())
               .takeUntil(_ => ackedRef.get().length > 0) // take until first successfull ack
               .runDrain
        initCount      <- initCountRef.get
        processedCount <- processedRef.get
        ackedCount      = ackedRef.get().length
        _              <- assertTrue(processedCount == ackedCount)
      yield assertTrue(initCount == failUntilAttempt)
    },
    test("stream fails after maximum retry attempts") {
      val testBidiStream = new TestBidiStream[StreamingPullRequest, StreamingPullResponse] {
        override def iterator(): ju.Iterator[StreamingPullResponse] = streamingPullResIterator(
          hasNextImpl = throw new Throwable("Some error")
        )
      }

      val maxRetries = 5
      val schedule   = Schedule.recurs(maxRetries)

      def initStream(counter: Ref[Int]) =
        counter.update(_ + 1).as(testBidiStream)

      for
        retryCounter <- Ref.make(-1)
        queue        <- Queue.unbounded[(String, AckOp)]
        exit         <- StreamingPullSubscriber.makeStream(initStream(retryCounter), queue, schedule).runDrain.exit
        retries      <- retryCounter.get
        _            <- assert(retries)(equalTo(maxRetries))
      yield assert(exit)(fails(anything))
    },
    test("all processed messages are acked or nacked on interruption") {
      check(Gen.int(1, 10000), Gen.boolean) { (interruptOnCount, interruptWithFailure) =>
        for
          processedRef     <- Ref.make(Vector.empty[String])
          ackedRef          = new AtomicReference(Vector.empty[String])
          nackedRef         = new AtomicReference(Vector.empty[String])
          ackQueue         <- Queue.unbounded[(String, AckOp)]
          interruptPromise <- Promise.make[Throwable, Unit]
          _                <- StreamingPullSubscriber
                 .makeStream(
                   ZIO.succeed(testBidiStream(ackedRef = ackedRef, nackedRef = nackedRef)),
                   ackQueue,
                   Schedule.stop,
                 )
                 .mapZIO { (msg, reply) =>
                   (for
                     c <- processedRef.updateAndGet(_ :+ msg.getAckId())
                     _ <- Live.live(Random.nextBoolean).flatMap {
                            case true  => reply.ack()
                            case false => reply.nack()
                          }
                   yield c.size).uninterruptible.flatMap {
                     case s if s >= interruptOnCount =>
                       if interruptWithFailure then interruptPromise.fail(new Throwable("interrupt with error"))
                       else interruptPromise.succeed(())
                     case _ => ZIO.unit
                   }
                 }
                 .interruptWhen(interruptPromise)
                 .runDrain
                 .exit
          processedAckIds  <- processedRef.get
          ackedAndNackedIds = ackedRef.get ++ nackedRef.get
          _                <- assertZIO(ackQueue.size)(equalTo(0))
          _                <- assertTrue(processedAckIds.size >= interruptOnCount)
          _                <- assertTrue(ackedAndNackedIds.size == processedAckIds.size)
          _                <- assert(processedAckIds)(hasSameElements(ackedAndNackedIds))
        yield assertCompletes
      }
    } @@ TestAspect.samples(20) @@ TestAspect.flaky, // TODO fix flaky test
    test("ack deadline of messages can be modified") {
      check(Gen.int(-100, 1000)) { seconds =>
        for
          modifiedRef <- ZIO.succeed(new AtomicReference(Vector.empty[(String, Int)]))
          ackQueue    <- Queue.unbounded[(String, AckOp)]
          ackIds      <- StreamingPullSubscriber
                      .makeStream(
                        ZIO.succeed(testBidiStream(modifiedDeadlinesRef = modifiedRef)),
                        ackQueue,
                        Schedule.stop,
                      )
                      .mapZIO((msg, reply) => reply.modifyAckDeadline(seconds).as(msg.getAckId()))
                      .take(3)
                      .runCollect
          expectedSeconds = math.max(0, math.min(600, seconds))
        yield assertTrue(modifiedRef.get() == ackIds.toVector.map((_, expectedSeconds)))
      }
    } @@ TestAspect.samples(20),
    test("outdated deadline modification is not sent after a newer one across request batches") {
      // newer 300s extension queued before an outdated 30s modification that was re-queued after a failure,
      // separated by more operations than fit into a single request
      val filler = Chunk.fromIterable((1 to 1500).map(i => s"filler-$i" -> AckOp.ModifyDeadline(60, seqNr = 100L + i)))
      val ops    = ("x" -> AckOp.ModifyDeadline(300, seqNr = 2)) +: filler :+ ("x" -> AckOp.ModifyDeadline(30, seqNr = 1))
      for
        queue   <- Queue.unbounded[(String, AckOp)]
        _       <- queue.offerAll(ops)
        sent     = new AtomicReference(Vector.empty[StreamingPullRequest])
        res     <- StreamingPullSubscriber.processAckQueue(queue, recordingClientStream(sent), waitForOps = true)
        requests = sent.get()
        modified = requests.flatMap(r =>
                     r.getModifyDeadlineAckIdsList.asScala.zip(r.getModifyDeadlineSecondsList.asScala.map(_.intValue))
                   )
      yield assertTrue(
        res.isEmpty,
        requests.size == 2,
        requests.forall(_.getModifyDeadlineAckIdsCount <= AckOp.maxAckIdsPerRequest),
        modified.size == 1501,
        modified.filter(_._1 == "x") == Vector("x" -> 300),
      )
    },
    test("failed request re-queues only unsent coalesced operations") {
      val ops = Chunk.fromIterable((1 to 1500).flatMap { i =>
        List(
          s"$i" -> AckOp.ModifyDeadline(30, seqNr = i.toLong),
          s"$i" -> AckOp.ModifyDeadline(300, seqNr = 10_000L + i),
        )
      })
      for
        queue  <- Queue.unbounded[(String, AckOp)]
        _      <- queue.offerAll(ops)
        sent    = new AtomicReference(Vector.empty[StreamingPullRequest])
        failed <- StreamingPullSubscriber.processAckQueue(
                    queue,
                    recordingClientStream(sent, failOnRequest = Some(2)),
                    waitForOps = true,
                  )
        requeued <- queue.takeAll
        sentIds   = sent.get().flatMap(_.getModifyDeadlineAckIdsList.asScala)
      yield assertTrue(
        failed.isDefined,
        sentIds.size == AckOp.maxAckIdsPerRequest,
        requeued.size == 1500 - AckOp.maxAckIdsPerRequest,
        requeued.forall { case (_, op) =>
          op.isInstanceOf[AckOp.ModifyDeadline] && op.asInstanceOf[AckOp.ModifyDeadline].seconds == 300
        },
        (sentIds ++ requeued.map(_._1)).sorted == (1 to 1500).map(_.toString).toVector.sorted,
      )
    },
    test("server stream is canceled on interruption (standalone)") {
      val cancelled      = new ju.concurrent.atomic.AtomicBoolean(false)
      val lock           = new AnyRef
      val testBidiStream = new TestBidiStream[StreamingPullRequest, StreamingPullResponse] {
        override def iterator(): ju.Iterator[StreamingPullResponse] = streamingPullResIterator(
          // hasNext that never returns until cancelled
          hasNextImpl = {
            lock.synchronized(while !cancelled.get() do lock.wait())
            false
          }
        )
        override def cancel(): Unit = {
          cancelled.set(true)
          lock.synchronized(lock.notify())
        }
      }

      for _ <- Live.live(
                 StreamingPullSubscriber
                   .makeServerStream(testBidiStream)
                   .timeout(500.millis)
                   .runDrain
               )
      yield assertTrue(cancelled.get)
    } @@ TestAspect.timeout(5.seconds),
    test("server stream is canceled on interruption when running with ack stream") {
      val cancelled      = new ju.concurrent.atomic.AtomicBoolean(false)
      val lock           = new AnyRef
      val testBidiStream = new TestBidiStream[StreamingPullRequest, StreamingPullResponse] {
        override def iterator(): ju.Iterator[StreamingPullResponse] = streamingPullResIterator(
          // hasNext that never returns until cancelled
          hasNextImpl = {
            lock.synchronized(while !cancelled.get() do lock.wait())
            false
          }
        )
        override def cancel(): Unit = {
          cancelled.set(true)
          lock.synchronized(lock.notify())
        }
      }

      for
        queue <- Queue.unbounded[(String, AckOp)]
        _     <- Live.live(
               StreamingPullSubscriber
                 .makeStream(ZIO.succeed(testBidiStream), queue, Schedule.forever)
                 .timeout(500.millis)
                 .runDrain
             )
      yield assertTrue(cancelled.get)
    },
  ) @@ TestAspect.timeout(60.seconds)

  def recordingClientStream(
    sent: AtomicReference[Vector[StreamingPullRequest]],
    failOnRequest: Option[Int] = None,
  ): TestBidiStream[StreamingPullRequest, StreamingPullResponse] =
    new TestBidiStream[StreamingPullRequest, StreamingPullResponse] {
      private val count                                = new ju.concurrent.atomic.AtomicInteger(0)
      override def send(r: StreamingPullRequest): Unit =
        if failOnRequest.contains(count.incrementAndGet()) then throw new Throwable("failed send")
        else { val _ = sent.updateAndGet(_ :+ r) }
      override def iterator(): ju.Iterator[StreamingPullResponse] = ju.Collections.emptyIterator()
    }

  def testBidiStream(
    failSend: Boolean = false,
    failPull: Boolean = false,
    ackedRef: AtomicReference[Vector[String]] = new AtomicReference[Vector[String]](Vector.empty),
    nackedRef: AtomicReference[Vector[String]] = new AtomicReference[Vector[String]](Vector.empty),
    modifiedDeadlinesRef: AtomicReference[Vector[(String, Int)]] =
      new AtomicReference[Vector[(String, Int)]](Vector.empty),
  ): BidiStream[StreamingPullRequest, StreamingPullResponse] =
    new TestBidiStream[StreamingPullRequest, StreamingPullResponse] {
      override def send(r: StreamingPullRequest): Unit =
        if failSend then throw new Throwable("failed ack")
        else {
          val _ = ackedRef.updateAndGet(_ ++ r.getAckIdsList.asScala.toVector)

          val modifyIds = r.getModifyDeadlineAckIdsList.asScala.toVector
          val deadlines = r.getModifyDeadlineSecondsList.asScala.toVector.map(_.intValue())

          // both have to have the same size, otherwise it's not a valid request
          if modifyIds.length != deadlines.length then {
            throw new Throwable("getModifyDeadlineAckIdsList / getModifyDeadlineSecondsList don't match in size")
          }

          val modified = modifyIds.zip(deadlines)
          val _        = modifiedDeadlinesRef.updateAndGet(_ ++ modified)
          // deadline needs to be set to 0 to nack a message
          val _ = nackedRef.updateAndGet(_ ++ modified.collect { case (id, 0) => id })
        }

      override def iterator(): ju.Iterator[StreamingPullResponse] = streamingPullResIterator(
        hasNextImpl = if failPull then throw new Throwable("fail pull") else true
      )
    }

  private def streamingPullResIterator(hasNextImpl: => Boolean): ju.Iterator[StreamingPullResponse] =
    new java.util.Iterator[StreamingPullResponse] {
      override def hasNext(): Boolean = hasNextImpl
      // return one message by default
      override def next(): StreamingPullResponse =
        StreamingPullResponse
          .newBuilder()
          .addReceivedMessages(ReceivedMessage.newBuilder().setAckId(ju.UUID.randomUUID().toString()).build())
          .build()
    }
}
