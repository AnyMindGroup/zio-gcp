package com.anymindgroup.gcp

import com.anymindgroup.gcp.auth.Credentials.ComputeServiceAccount
import com.anymindgroup.gcp.auth.{AccessToken, IdToken}
import sttp.client4.*
import sttp.model.{Header, Uri}

object ComputeMetadata {
  private[gcp] val baseUri = uri"http://metadata.google.internal/computeMetadata/v1"
  private[gcp] val baseReq = quickRequest.header(Header("Metadata-Flavor", "Google"))

  // https://cloud.google.com/compute/docs/metadata/predefined-metadata-keys#project-metadata
  val projectUri: Uri                      = baseUri.addPath("project")
  val projectIdReq: Request[String]        = baseReq.get(projectUri.addPath("project-id"))
  val numericProjectIdReq: Request[String] = baseReq.get(projectUri.addPath("numeric-project-id"))

  // https://cloud.google.com/compute/docs/metadata/predefined-metadata-keys#instance-metadata
  val instanceUri: Uri                 = baseUri.addPath("instance")
  val instanceZoneReq: Request[String] = baseReq.get(instanceUri.addPath("zone"))

  val serviceAccountUri: Uri = instanceUri.addPath("service-accounts", "default")

  val serviceAccountEmailReq: Request[ComputeServiceAccount] =
    baseReq.get(serviceAccountUri.addPath("email")).mapResponse(ComputeServiceAccount(_))

  val serviceAccountEmailAccessTokenReq: Request[Either[Throwable, AccessToken]] =
    baseReq
      .get(serviceAccountUri.addPath("token"))
      .mapResponse(AccessToken.fromJsonString(_))

  def serviceAccountIdTokenReq(audience: String): Request[Either[Throwable, IdToken]] =
    baseReq.get(serviceAccountUri.addPath("identity").addParam("audience", audience)).mapResponse(IdToken.fromString(_))
}
