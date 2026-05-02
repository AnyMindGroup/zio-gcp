import com.anymindgroup.gcp.pubsub.v1.*
import com.anymindgroup.pubsub.*
import com.anymindgroup.pubsub.http.{makeAuthedBackend, makeSubscriber, makeTopicPublisher}
import sttp.client4.ResponseException.UnexpectedStatusCode
import sttp.client4.Backend
import zio.ZIO.logInfo
import com.anymindgroup.gcp.auth.AuthedBackend
import zio.{ZIO, ZLayer, Task}

object ZIOPubsubTestHttp extends ZIOPubsubTestApp with ZIOPubsubTestRuntime:
  private def pubLayer =
    ZLayer.scoped:
      for
        c <- ZIO.service[PubsubConnectionConfig]
        t <- ZIO.service[TopicName]
        b <- ZIO.service[AuthedBackend]
        p <- makeTopicPublisher(
               topicName = t,
               serializer = Serde.utf8String,
               connection = c,
               backend = Some(b),
             )
      yield p

  private def subLayer =
    ZLayer.scoped:
      for
        c  <- ZIO.service[PubsubConnectionConfig]
        b  <- ZIO.service[Backend[Task]]
        tc <- ZIO.service[TestRunConfig]
        _  <- ZIO.logInfo(s"Making subsciber with maxMessagesPerPull = ${tc.batchSize}")
        s  <- makeSubscriber(connection = c, backend = Some(b), maxMessagesPerPull = tc.batchSize)
      yield s

  private def backendLayer =
    ZLayer.scoped:
      for
        connection <- ZIO.service[PubsubConnectionConfig]
        backend    <- makeAuthedBackend(connection)
      yield backend

  def testBootstrap =
    (backendLayer >+>
      ZLayer.fromZIO {
        for
          backend <- ZIO.service[AuthedBackend]
          topic   <- ZIO.service[TopicName]
          sub     <- ZIO.service[SubscriptionName]
          _       <- logInfo(s"⏳ Creating topic ${topic.fullName}...")
          _       <- resources.projects.Topics
                 .create(
                   projectsId = topic.projectId,
                   topicsId = topic.topic,
                   request = schemas.Topic(name = topic.fullName),
                 )
                 .send(backend)
                 .map(_.body)
                 .flatMap:
                   case Right(t)                                               => logInfo(s"✅ Topic ${t.name} created")
                   case Left(UnexpectedStatusCode(_, r)) if r.code.code == 409 =>
                     logInfo(s"✅ Topic ${topic.fullName} exists")
                   case Left(err) => ZIO.fail(err)
          _ <- logInfo(s"⏳ Creating subscription ${sub.fullName}...")
          _ <- resources.projects.Subscriptions
                 .create(
                   projectsId = sub.projectId,
                   subscriptionsId = sub.subscription,
                   request = schemas.Subscription(
                     name = sub.fullName,
                     topic = topic.fullName,
                   ),
                 )
                 .send(backend)
                 .map(_.body)
                 .flatMap:
                   case Left(UnexpectedStatusCode(_, r)) if r.code.code == 409 =>
                     logInfo(s"✅ Subscription ${sub.fullName} exists")
                   case Left(err) => ZIO.fail(err)
                   case Right(s)  => logInfo(s"✅ Subscription ${s.name} created")
        yield ()
      }) >>> (pubLayer ++ subLayer)
