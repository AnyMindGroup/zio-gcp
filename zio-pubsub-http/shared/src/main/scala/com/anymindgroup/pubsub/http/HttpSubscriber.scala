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
  private def processAckQueue(chunkSizeLimit: Option[Int], subName: SubscriptionName): UIO[Option[Cause[Throwable]]] =
    chunkSizeLimit
      .fold(ackQueue.takeAll)(ackQueue.takeBetween(1, _))
      .flatMap { c =>
        val (ackIds, modifyDeadlines) = HttpSubscriber.batchAckOps(c)

        val requests = NonEmptyChunk.fromChunk(ackIds).map(sendAck(_, subName)).toList ++
          modifyDeadlines.map((seconds, ids) => sendModifyDeadline(ids, seconds, subName))

        ZIO
          .collectAllPar(requests)
          .map(_.flatten.reduceOption(_ && _))
      }

  private def sendModifyDeadline(ackIds: NonEmptyChunk[String], seconds: Int, subName: SubscriptionName) =
    p.Subscriptions
      .modifyAckDeadline(
        projectsId = subName.projectId,
        subscriptionsId = subName.subscription,
        request = s.ModifyAckDeadlineRequest(ackIds = ackIds, ackDeadlineSeconds = seconds),
      )
      .send(backend)
      .flatMap(r => ZIO.fromEither(r.body))
      .uninterruptible
      .as(None)
      .catchAllCause(c => ackQueue.offerAll(ackIds.map((_, AckOp.ModifyDeadline(seconds)))).as(Some(c)))

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
                          ackQueue.offer((ackId, AckOp.ModifyDeadline(AckReply.clampAckDeadline(seconds)))).unit
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
        processAckQueue(Some(1024), subscriptionName).flatMap {
          case None    => ZIO.unit
          case Some(c) => ZIO.failCause(c)
        }

    pullStream.drainFork(ackStream).onError(_ => processAckQueue(None, subscriptionName)).retry(retrySchedule)
  }
}

object HttpSubscriber {

  /**
   * Splits queued ack operations into ack ids and modify deadline requests
   * grouped by deadline.
   *
   * ModifyAckDeadlineRequest supports only a single deadline value per request
   * and the requests are sent in parallel. Only the latest queued deadline per
   * ack id is kept so that an earlier modification can't override a later one.
   */
  private[http] def batchAckOps(ops: Chunk[(String, AckOp)]): (Chunk[String], List[(Int, NonEmptyChunk[String])]) = {
    val (ackIds, modifyDeadlines) = ops.partitionMap {
      case (id, AckOp.Ack)                     => Left(id)
      case (id, AckOp.ModifyDeadline(seconds)) => Right((id, seconds))
    }

    val latestDeadlineById = modifyDeadlines.toMap // later entries take precedence

    val modifyDeadlineGroups = latestDeadlineById.toList
      .groupMap(_._2)(_._1)
      .toList
      .flatMap((seconds, ids) => NonEmptyChunk.fromIterableOption(ids).map((seconds, _)))

    (ackIds, modifyDeadlineGroups)
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
