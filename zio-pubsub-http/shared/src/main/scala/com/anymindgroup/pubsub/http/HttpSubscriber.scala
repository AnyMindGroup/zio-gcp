package com.anymindgroup.pubsub.http

import java.util.Base64
import java.util.Base64.Decoder

import zio.stream.ZStream
import zio.{Cause, Chunk, NonEmptyChunk, Queue, Schedule, Scope, Task, UIO, ZIO}

import com.anymindgroup.gcp.auth.{
  AuthedBackend,
  Token,
  TokenProvider,
  TokenProviderException,
  defaultAccessTokenBackend,
  toAuthedBackend,
}
import com.anymindgroup.gcp.pubsub.v1.resources.projects as p
import com.anymindgroup.gcp.pubsub.v1.schemas as s
import com.anymindgroup.http.*
import com.anymindgroup.pubsub.*
import sttp.client4.*

class HttpSubscriber private[http] (
  backend: Backend[Task],
  maxMessagesPerPull: Int,
  ackQueue: Queue[(String, AckOp)],
  retrySchedule: Schedule[Any, Throwable, ?],
  base64Decoder: Decoder,
  returnImmediatelyOnPull: Boolean,
) extends Subscriber {
  private def processAckQueue(waitForOps: Boolean, subName: SubscriptionName): UIO[Option[Cause[Throwable]]] =
    (if waitForOps then AckOp.takeAllAvailable(ackQueue) else ackQueue.takeAll).flatMap { c =>
      val (ackBatches, modifyDeadlineBatches) = HttpSubscriber.batchAckOps(c)

      val requests = ackBatches.map(sendAck(_, subName)) ++
        modifyDeadlineBatches.map((seconds, ops) => sendModifyDeadline(ops, seconds, subName))

      ZIO
        .collectAllPar(requests)
        .withParallelism(HttpSubscriber.maxParallelAckRequests)
        .map(_.flatten.reduceOption(_ && _))
    }

  private def sendModifyDeadline(
    ops: NonEmptyChunk[(String, AckOp.ModifyDeadline)],
    seconds: Int,
    subName: SubscriptionName,
  ) =
    p.Subscriptions
      .modifyAckDeadline(
        projectsId = subName.projectId,
        subscriptionsId = subName.subscription,
        request = s.ModifyAckDeadlineRequest(ackIds = ops.map(_._1), ackDeadlineSeconds = seconds),
      )
      .send(backend)
      .flatMap(r => ZIO.fromEither(r.body))
      .uninterruptible
      .as(None)
      // re-queue the original operations to retain their sequence numbers
      .catchAllCause(c => ackQueue.offerAll(ops).as(Some(c)))

  private def sendAck(ackIds: NonEmptyChunk[String], subName: SubscriptionName) =
    p.Subscriptions
      .acknowledge(
        projectsId = subName.projectId,
        subscriptionsId = subName.subscription,
        request = s.AcknowledgeRequest(ackIds = ackIds),
      )
      .send(backend)
      .flatMap(r => ZIO.fromEither(r.body))
      .uninterruptible
      .as(None)
      .catchAllCause(c => ackQueue.offerAll(ackIds.map((_, AckOp.Ack))).as(Some(c)))

  private[pubsub] def pull(
    subscriptionName: SubscriptionName,
    returnImmediately: Boolean = returnImmediatelyOnPull,
    maxMessages: Int = maxMessagesPerPull,
  ): ZIO[Any, Throwable, Chunk[(ReceivedMessage[Chunk[Byte]], AckReply)]] =
    p.Subscriptions
      .pull(
        projectsId = subscriptionName.projectId,
        subscriptionsId = subscriptionName.subscription,
        request = s.PullRequest(maxMessages = maxMessages, returnImmediately = Some(returnImmediately)),
      )
      .send(backend)
      .flatMap { res =>
        res.body match {
          case Left(err)    => ZIO.fail(err)
          case Right(value) =>
            ZIO.succeed(
              value.receivedMessages
                .getOrElse(Chunk.empty)
                .map(m =>
                  (
                    m.message.flatMap(_.messageId),
                    m.message.flatMap(_.publishTime),
                    m.message.flatMap(_.attributes),
                    m.message.flatMap(_.data),
                    m.message.flatMap(_.orderingKey),
                    m.deliveryAttempt,
                    m.ackId,
                  )
                )
                .collect {
                  case (
                        Some(mId),
                        Some(ts),
                        attrs,
                        data,
                        orderingKey,
                        deliveryAttempt,
                        Some(ackId),
                      ) =>
                    (
                      ReceivedMessage(
                        meta = ReceivedMessage.Metadata(
                          messageId = MessageId(mId),
                          ackId = AckId(ackId),
                          publishTime = ts.toInstant(),
                          orderingKey = orderingKey.flatMap(OrderingKey.fromString(_)),
                          attributes = attrs.getOrElse(Map.empty),
                          deliveryAttempt = deliveryAttempt.getOrElse(0),
                        ),
                        data = data match {
                          case None        => Chunk.empty[Byte]
                          case Some(value) => Chunk.fromArray(base64Decoder.decode(value))
                        },
                      ),
                      new AckReply {
                        override def ack(): UIO[Unit] =
                          ackQueue.offer((ackId, AckOp.Ack)).unit
                        override def modifyAckDeadline(seconds: Int): UIO[Unit] =
                          AckOp.modifyDeadline(seconds).flatMap(op => ackQueue.offer((ackId, op))).unit
                      },
                    )
                }
            )
        }
      }

  override def subscribeRaw(subscriptionName: SubscriptionName): ZStream[Any, Throwable, RawReceipt] = {
    val pullStream = ZStream.repeatZIOChunk(pull(subscriptionName))

    val ackStream: ZStream[Any, Throwable, Unit] = ZStream
      .repeatZIO:
        processAckQueue(waitForOps = true, subscriptionName).flatMap {
          case None    => ZIO.unit
          case Some(c) => ZIO.failCause(c)
        }

    pullStream
      .drainFork(ackStream)
      .onError(_ => processAckQueue(waitForOps = false, subscriptionName))
      .retry(retrySchedule)
  }
}

object HttpSubscriber {

  private val maxParallelAckRequests = 8

  /**
   * Coalesces queued ack operations and splits them into acknowledge requests
   * and modify deadline requests grouped by deadline (ModifyAckDeadlineRequest
   * supports only a single deadline value per request).
   *
   * Each ack id is contained in at most one request, so the requests can be
   * sent in parallel. Only the most recently requested deadline per ack id is
   * kept so that an earlier modification can't override a later one, also when
   * failed modifications were re-queued after newer ones.
   */
  private[http] def batchAckOps(
    ops: Chunk[(String, AckOp)]
  ): (List[NonEmptyChunk[String]], List[(Int, NonEmptyChunk[(String, AckOp.ModifyDeadline)])]) = {
    val (ackIds, latestModifications) = AckOp.partition(AckOp.coalesce(ops))

    val ackBatches = ackIds.grouped(AckOp.maxAckIdsPerRequest).flatMap(NonEmptyChunk.fromChunk).toList

    val modifyDeadlineBatches = latestModifications.toList
      .groupBy(_._2.seconds)
      .toList
      .flatMap: (seconds, ops) =>
        ops.grouped(AckOp.maxAckIdsPerRequest).flatMap(NonEmptyChunk.fromIterableOption).map((seconds, _))

    (ackBatches, modifyDeadlineBatches)
  }

  object defaults:
    val maxMessagesPerPull: Int                      = 100
    val retrySchedule: Schedule[Any, Throwable, Any] = Schedule.recurs(5)
    // From pubsub docs:
    // If this field set to true, the system will respond immediately even if it there are no messages available to return in the `Pull` response
    // Otherwise, the system may wait (for a bounded amount of time) until at least one message is available, rather than returning no messages
    // Warning: setting this field to `true` is discouraged because it adversely impacts the performance of `Pull` operations
    // We recommend that users do not set this field.
    val returnImmediatelyOnPull: Boolean = false

  private[pubsub] def makeFromAuthedBackend(
    authedBackend: AuthedBackend,
    maxMessagesPerPull: Int,
    retrySchedule: Schedule[Any, Throwable, ?],
    returnImmediatelyOnPull: Boolean,
  ): ZIO[Scope, Nothing, HttpSubscriber] =
    ZIO
      .acquireRelease(Queue.unbounded[(String, AckOp)])(_.shutdown)
      .map: ackQueue =>
        HttpSubscriber(
          backend = authedBackend,
          maxMessagesPerPull = maxMessagesPerPull,
          ackQueue = ackQueue,
          retrySchedule = retrySchedule,
          base64Decoder = Base64.getDecoder(),
          returnImmediatelyOnPull = returnImmediatelyOnPull,
        )

  def make(
    backend: Backend[Task],
    tokenProvider: TokenProvider[Token],
    maxMessagesPerPull: Int = defaults.maxMessagesPerPull,
    retrySchedule: Schedule[Any, Throwable, ?] = defaults.retrySchedule,
    returnImmediatelyOnPull: Boolean = defaults.returnImmediatelyOnPull,
  ): ZIO[Scope, Nothing, HttpSubscriber] =
    makeFromAuthedBackend(
      authedBackend = toAuthedBackend(tokenProvider, backend),
      maxMessagesPerPull = maxMessagesPerPull,
      retrySchedule = retrySchedule,
      returnImmediatelyOnPull = returnImmediatelyOnPull,
    )

  def makeWithDefaultTokenProvider(
    backend: Backend[Task],
    maxMessagesPerPull: Int = defaults.maxMessagesPerPull,
    retrySchedule: Schedule[Any, Throwable, ?] = defaults.retrySchedule,
    authConfig: AuthConfig = AuthConfig.default,
    returnImmediatelyOnPull: Boolean = defaults.returnImmediatelyOnPull,
  ): ZIO[Scope, TokenProviderException, HttpSubscriber] =
    TokenProvider
      .defaultAccessTokenProvider(
        backend = backend,
        lookupComputeMetadataFirst = authConfig.lookupComputeMetadataFirst,
        refreshRetrySchedule = authConfig.tokenRefreshRetrySchedule,
        refreshAtExpirationPercent = authConfig.tokenRefreshAtExpirationPercent,
      )
      .flatMap: tokenProvider =>
        make(
          backend = backend,
          tokenProvider = tokenProvider,
          maxMessagesPerPull = maxMessagesPerPull,
          retrySchedule = retrySchedule,
        )

  def makeWithDefaultBackend(
    connection: PubsubConnectionConfig,
    maxMessagesPerPull: Int = defaults.maxMessagesPerPull,
    retrySchedule: Schedule[Any, Throwable, ?] = defaults.retrySchedule,
    authConfig: AuthConfig = AuthConfig.default,
    returnImmediatelyOnPull: Boolean = defaults.returnImmediatelyOnPull,
  ): ZIO[Scope, Throwable, HttpSubscriber] =
    connection match
      case emulator: PubsubConnectionConfig.Emulator =>
        httpBackendScoped().flatMap: backend =>
          makeFromAuthedBackend(
            authedBackend = EmulatorBackend(backend, emulator),
            maxMessagesPerPull = maxMessagesPerPull,
            retrySchedule = retrySchedule,
            returnImmediatelyOnPull = returnImmediatelyOnPull,
          )
      case PubsubConnectionConfig.Cloud =>
        defaultAccessTokenBackend(
          lookupComputeMetadataFirst = authConfig.lookupComputeMetadataFirst,
          refreshRetrySchedule = authConfig.tokenRefreshRetrySchedule,
          refreshAtExpirationPercent = authConfig.tokenRefreshAtExpirationPercent,
        ).flatMap: authedBackend =>
          makeFromAuthedBackend(
            authedBackend = authedBackend,
            maxMessagesPerPull = maxMessagesPerPull,
            retrySchedule = retrySchedule,
            returnImmediatelyOnPull = returnImmediatelyOnPull,
          )
}
