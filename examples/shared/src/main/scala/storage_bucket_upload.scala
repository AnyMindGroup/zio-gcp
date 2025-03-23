//> using scala 3.6.4
//> using dep com.anymindgroup::zio-gcp-auth::0.1.2
//> using dep com.anymindgroup::zio-gcp-storage-v1::0.1.2

import zio.*, com.anymindgroup.gcp.*, auth.defaultAccessTokenBackend
import storage.v1.resources.Objects, sttp.model.{Header, MediaType}

object storage_bucket_upload extends ZIOAppDefault:
  def run =
    for
      backend   <- defaultAccessTokenBackend()
      bucket     = "my-bucket"
      objName    = "folder/my_file.txt"
      objContent = "my file content".getBytes("UTF-8")
      // insert file
      _ <- backend
             .send(
               Objects
                 .insert(bucket = bucket, name = Some(objName))
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
      // delete file
      _ <- backend
             .send(Objects.delete(`object` = objName, bucket = bucket))
             .flatMap:
               _.body match
                 case Right(body) => ZIO.logInfo(s"Object deleted.")
                 case Left(err)   => ZIO.logError(s"Failure on deleting: $err")
    yield ()
