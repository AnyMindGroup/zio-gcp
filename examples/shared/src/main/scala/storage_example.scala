//> using scala 3.7.4
//> using dep com.anymindgroup::zio-gcp-auth::0.2.5
//> using dep com.anymindgroup::zio-gcp-storage::0.2.5

import zio.*, com.anymindgroup.gcp.*, storage.*, auth.defaultAccessTokenBackend
import v1.resources.Objects, sttp.model.{Header, MediaType, Method}

object storage_example extends ZIOAppDefault:
  def run =
    for
      backend   <- defaultAccessTokenBackend()
      bucket     = "my-bucket"
      objPath    = List("folder", "my_file.txt")
      objContent = "my file content".getBytes("UTF-8")
      // insert file
      _ <- backend
             .send(
               Objects
                 .insert(bucket = bucket, name = Some(objPath.mkString("/")))
                 .headers(
                   Header.contentType(MediaType.TextPlain),
                   Header.contentLength(objContent.length),
                 )
                 .body(objContent)
             )
             .map(_.body)
             .flatMap:
               case Right(body) => ZIO.logInfo(s"Upload ok: $body")
               case Left(err)   => ZIO.dieMessage(s"Failure on upload: $err")

      // create signed url
      signedUrl <- V4SignUrlRequestBuilder
                     .create()
                     .signUrlRequest(
                       bucket = bucket,
                       resourcePath = objPath,
                       contentType = None,
                       method = Method.GET,
                       serviceAccountEmail = "example@example-project.iam.gserviceaccount.com",
                       signAlgorithm = V4SignAlgorithm.`GOOG4-RSA-SHA256`,
                       expiresInSeconds = V4SignatureExpiration.inSeconds(300),
                     )
                     .flatMap(_.send(backend).flatMap(r => ZIO.fromEither(r.body)))
      _ <- ZIO.logInfo(s"✅ Created signed url: $signedUrl")

      // delete file
      _ <- backend
             .send(Objects.delete(`object` = objPath.mkString("/"), bucket = bucket))
             .flatMap:
               _.body match
                 case Right(body) => ZIO.logInfo(s"Object deleted.")
                 case Left(err)   => ZIO.logError(s"Failure on deleting: $err")
    yield ()
