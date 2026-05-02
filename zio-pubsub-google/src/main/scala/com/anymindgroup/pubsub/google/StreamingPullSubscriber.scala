package com.anymindgroup.pubsub.google

import java.util as ju

import scala.jdk.CollectionConverters.*

import com.anymindgroup.pubsub.{AckReply, PubsubConnectionConfig, SubscriptionName}
import com.google.api.gax.rpc.{BidiStream as GBidiStream, ClientStream}
import com.google.cloud.pubsub.v1.stub.{GrpcSubscriberStub, SubscriberStubSettings}
import com.google.cloud.pubsub.v1.{SubscriptionAdminClient, SubscriptionAdminSettings}
import com.google.pubsub.v1.{
  ReceivedMessage as GReceivedMessage,
  StreamingPullRequest,
  StreamingPullResponse,
  SubscriptionName as GSubscriptionName,
}

import zio.stream.{ZStream, ZStreamAspect}
import zio.{Cause, Chunk, Promise, Queue, RIO, Schedule, Scope, Task, UIO, ZIO, ZIOAspect, durationInt}

private[pubsub] object StreamingPullSubscriber {
  val defaultRetrySchedule: Schedule[Any, Throwable, Any] = {
    val maxConsecutiveAttempts   = 5
    val consecutiveAttemptWithin = 10.seconds

    (Schedule.recurs(maxConsecutiveAttempts) && Schedule.identity[Throwable])
      .resetAfter(consecutiveAttemptWithin)
      .addDelayZIO { (consecutiveAttemptCount, err) =>
        val delay = 1.second

        (err match
          // https://cloud.google.com/pubsub/docs/pull#streamingpull_api
          // The StreamingPull API keeps an open connection.
          // The Pub/Sub servers recurrently close the connection after a time period to avoid a long-running sticky connection.
          case _: com.google.api.gax.rpc.UnavailableException =>
            ZIO.logInfo(s"Recovering connection in ${delay.getSeconds()}s").as(delay)
          case _ =>
            ZIO
              .logInfoCause(
                s"Trying to recover connection in ${delay.toSeconds()}s from unexpected cause",
                Cause.fail(err),
              )
              .as(delay)
        ) @@ ZIOAspect.annotated(
          "consecutiveAttempts" -> s"${consecutiveAttemptCount.toString()} / ${maxConsecutiveAttempts.toString()}"
        )
      }
  }

  private[pubsub] def makeServerStream(
    stream: ServerStream[StreamingPullResponse]
  ): ZStream[Any, Throwable, GReceivedMessage] =
    ZStream
      .unfoldChunkZIO(stream.iterator())(it =>
        ZIO
          .attemptBlockingCancelable((it.hasNext()))((ZIO.succeed(stream.cancel())))
          .map {
            case true  => Some((Chunk.fromJavaIterable(it.next().getReceivedMessagesList), it))
            case false => None
          }
      )

  private def processAckQueue(
    ackQueue: Queue[(String, Boolean)],
    clientStream: ClientStream[StreamingPullRequest],
    chunkSizeLimit: Option[Int],
  ): UIO[Option[Cause[Throwable]]] =
    chunkSizeLimit
      .fold(ackQueue.takeAll)(ackQueue.takeBetween(1, _))
      .flatMap {
        case chunk if chunk.isEmpty => ZIO.none
        case chunk                  =>
          ZIO.attempt {
            val (ackIds, nackIds) = chunk.partitionMap {
              case (id, true)  => Left(id)
              case (id, false) => Right(id)
            }

            val req = StreamingPullRequest.newBuilder
              .addAllAckIds(ackIds.asJava)
              .addAllModifyDeadlineSeconds(nackIds.map(_ => Integer.valueOf(0)).asJava)
              .addAllModifyDeadlineAckIds(nackIds.asJava)
              .build()

            clientStream.send(req)
          }.uninterruptible
            .as(None)
            .catchAllCause { c =>
              // place back into the queue on failures
              ackQueue.offerAll(chunk).as(Some(c))
            }
      }

  private[pubsub] def makeStream(
    initBidiStream: Task[BidiStream[StreamingPullRequest, StreamingPullResponse]],
    ackQueue: Queue[(String, Boolean)],
    retrySchedule: Schedule[Any, Throwable, ?],
  ): ZStream[Any, Throwable, (GReceivedMessage, AckReply)] = (for {
    bidiStream <- ZStream.fromZIO(initBidiStream)
    stream      = makeServerStream(bidiStream).map { message =>
               val ackReply = new AckReply {
                 override def ack(): UIO[Unit]  = ackQueue.offer((message.getAckId(), true)).uninterruptible.unit
                 override def nack(): UIO[Unit] = ackQueue.offer((message.getAckId(), false)).uninterruptible.unit
               }
               (message, ackReply)
             }
    ackStream = ZStream.repeatZIO(
                  processAckQueue(ackQueue, bidiStream, Some(1024)).flatMap {
                    case None    => ZIO.unit
                    case Some(c) => ZIO.failCause(c)
                  }
                )
    ackStreamFailed <- ZStream.fromZIO(Promise.make[Throwable, Nothing])
    _               <-
      ZStream.scopedWith { scope =>
        for {
          _ <-
            scope.addFinalizerExit { _ =>
              for {
                // cancel receiving stream before processing the rest of the queue
                _ <- ZIO.succeed(bidiStream.cancel())
                _ <- processAckQueue(ackQueue, bidiStream, None)
              } yield ()
            }
          _ <- ackStream.channel.drain.runIn(scope).catchAllCause(ackStreamFailed.failCause(_)).forkIn(scope)
        } yield ()
      }
    s <- stream.interruptWhen(ackStreamFailed)
  } yield s).retry(retrySchedule)

  private[pubsub] def initGrpcBidiStream(
    subscriber: GrpcSubscriberStub,
    subscriptionId: GSubscriptionName,
    streamAckDeadlineSeconds: Int,
  ): Task[BidiStream[StreamingPullRequest, StreamingPullResponse]] = for {
    _          <- ZIO.log(s"Initializing subscription bidi stream...")
    bidiStream <- ZIO.attempt {
                    val gBidiStream = subscriber.streamingPullCallable().call()
                    val req         =
                      StreamingPullRequest.newBuilder
                        .setSubscription(subscriptionId.toString())
                        .setStreamAckDeadlineSeconds(streamAckDeadlineSeconds)
                        .build()
                    gBidiStream.send(req)
                    BidiStream.fromGrpcBidiStream(gBidiStream)
                  }
    _ <- ZIO.log(s"Subscription bidi stream initialized")
  } yield bidiStream

  private def getAckDeadlineSeconds(
    connection: PubsubConnectionConfig,
    subscriptionName: GSubscriptionName,
  ) =
    ZIO.scoped(
      createClient(SubscriptionAdminSettings.newBuilder(), SubscriptionAdminClient.create(_), connection).flatMap: s =>
        ZIO.attempt(s.getSubscription(subscriptionName).getAckDeadlineSeconds())
    )

  def makeRawStream(
    subscriptionName: SubscriptionName,
    retrySchedule: Schedule[Any, Throwable, ?],
    connection: PubsubConnectionConfig,
  ): RIO[Scope, GoogleStream] = for {
    subscriptionId     <- ZIO.succeed(GSubscriptionName.of(subscriptionName.projectId, subscriptionName.subscription))
    ackDeadlineSeconds <- getAckDeadlineSeconds(connection, subscriptionId)
    subscriber         <- createStub(connection, SubscriberStubSettings.newBuilder, GrpcSubscriberStub.create(_))
    ackQueue           <- ZIO.acquireRelease(Queue.unbounded[(String, Boolean)])(_.shutdown)
    stream              =
      makeStream(
        initGrpcBidiStream(subscriber, subscriptionId, ackDeadlineSeconds),
        ackQueue,
        retrySchedule,
      ) @@ ZStreamAspect.annotated(
        "subscription_name"         -> subscriptionName.fullName,
        "subscription_ack_deadline" -> s"${ackDeadlineSeconds}s",
      )
  } yield stream
}

// interface for com.google.api.gax.rpc.BidiStream without implementations of com.google.api.gax.rpc.ServerStream to be enable to reproduce behaviour in tests
private[pubsub] trait BidiStream[A, B] extends ClientStream[A] with ServerStream[B]
private[pubsub] object BidiStream {
  def fromGrpcBidiStream[A, B](s: GBidiStream[A, B]): BidiStream[A, B] = new BidiStream[A, B] {
    override def send(request: A): Unit                 = s.send(request)
    override def closeSendWithError(t: Throwable): Unit = s.closeSendWithError(t)
    override def closeSend(): Unit                      = s.closeSend()
    override def isSendReady(): Boolean                 = s.isSendReady()
    override def iterator(): ju.Iterator[B]             = s.iterator()
    override def cancel(): Unit                         = s.cancel()
  }
}

// interface for com.google.api.gax.rpc.ServerStream
private[pubsub] trait ServerStream[T] extends java.lang.Iterable[T] {
  def cancel(): Unit
}
