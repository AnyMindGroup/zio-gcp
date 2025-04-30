package com.anymindgroup.gcp
package storage

import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Base64.Encoder

import com.anymindgroup.gcp.iamcredentials.v1.resources.projects.ServiceAccounts
import com.anymindgroup.gcp.iamcredentials.v1.schemas.SignBlobRequest
import sttp.client4.Request
import sttp.model.{MediaType, Method, QueryParams, Uri}

import zio.{Clock, Task, ZIO}

class V4SignUrlRequestBuilder private (
  canonicalRequestBuilder: V4CanonicalRequestBuilder,
  encoder: Encoder,
) {
  def signUrlRequest(
    bucket: String,
    resourcePath: Seq[String],
    contentType: Option[MediaType],
    method: Method,
    serviceAccountEmail: String,
    signAlgorithm: V4SignAlgorithm,
    expiresInSeconds: V4SignatureExpiration,
  ): Task[Request[Either[Throwable, Uri]]] =
    for {
      now <- Clock.instant
      canonicalReq <- ZIO.fromEither(
                        canonicalRequestBuilder
                          .toCanonicalRequest(
                            method = method,
                            timestamp = now,
                            resourcePath = resourcePath,
                            contentType = contentType,
                            bucket = bucket,
                            serviceAccountEmail = serviceAccountEmail,
                            signAlgorithm = signAlgorithm,
                            expiresInSeconds = expiresInSeconds,
                          )
                      )
      signPayload = encoder.encodeToString(canonicalReq.stringToSign.getBytes(StandardCharsets.UTF_8))
      req = ServiceAccounts
              .signBlob(
                projectsId = "-",
                serviceAccountsId = serviceAccountEmail,
                request = SignBlobRequest(payload = signPayload),
              )
              .mapResponse {
                case Left(err) => Left(err)
                case Right(signatureRes) =>
                  V4SignUrlRequestBuilder.toSignedUrl(
                    signatureResponse = signatureRes.signedBlob.getOrElse(""),
                    canonicalQueryParams = canonicalReq.canonicalQueryParams,
                    bucket = bucket,
                    resourcePath = resourcePath,
                  )
              }
    } yield req
}

object V4SignUrlRequestBuilder {
  def create(): V4SignUrlRequestBuilder = V4SignUrlRequestBuilder(
    canonicalRequestBuilder = V4CanonicalRequestBuilder(),
    encoder = Base64.getEncoder(),
  )

  private[storage] def toSignedUrl(
    signatureResponse: String,
    canonicalQueryParams: QueryParams,
    bucket: String,
    resourcePath: Seq[String],
  ): Either[Throwable, Uri] =
    (try {
      val decoded = Base64.getDecoder().decode(signatureResponse)
      Right(decoded.map("%02x".format(_)).mkString)
    } catch {
      case e: Throwable => Left(e)
    }).map: signature =>
      storage.v1.rootUrl
        .addPath(bucket, resourcePath*)
        .addParams(canonicalQueryParams.param("X-Goog-Signature", signature))

}
