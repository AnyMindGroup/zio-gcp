//> using scala 3.6.3
//> using dep com.anymindgroup::zio-gcp-auth::0.0.4
//> using dep com.anymindgroup::zio-gcp-storage-v1::0.0.4

import zio.*, Console.{printLine, printError}
import com.anymindgroup.gcp.auth.defaultAccessTokenBackend
import com.anymindgroup.gcp.storage.v1.resources.Objects
import sttp.model.{Header, MediaType}
import java.nio.charset.StandardCharsets

object storage_bucket_upload extends ZIOAppDefault:
  def run =
    for
      backend   <- defaultAccessTokenBackend()
      bucket     = "my-bucket"
      objName    = "folder/my_file.txt"
      objContent = "my file content".getBytes(StandardCharsets.UTF_8)
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
               case Right(body) => printLine(s"Upload ok: $body")
               case Left(err)   => ZIO.dieMessage(s"Failure on upload: $err")
      // delete file
      _ <- backend
             .send(Objects.delete(`object` = objName, bucket = bucket))
             .map(_.body)
             .flatMap:
               case Right(body) => printLine(s"Object deleted.")
               case Left(err)   => printError(s"Failure on deleting object: $err")
    yield ()
