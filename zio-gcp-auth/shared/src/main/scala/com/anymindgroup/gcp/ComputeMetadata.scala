package com.anymindgroup.gcp

import com.anymindgroup.gcp.auth.Credentials.ComputeServiceAccount
import com.anymindgroup.gcp.auth.{AccessToken, IdToken}
import sttp.client4.*
import sttp.model.{Header, Uri}

object ComputeMetadata {

  /** Authority of the metadata server when `GCE_METADATA_HOST` says nothing. */
  private[gcp] val defaultHost = "metadata.google.internal"

  /**
   * Overrides the metadata server's authority.
   *
   * The same variable Google's own client libraries read — Go, Python and Java
   * all consult `GCE_METADATA_HOST` before falling back to
   * `metadata.google.internal` — so an environment already set up for one of
   * those works here without further configuration.
   *
   * Its value is an authority (`host` or `host:port`) and carries no scheme,
   * matching those libraries. This is what makes a metadata *emulator* usable:
   * an emulator generally listens on a loopback port, and `metadata.google
   * .internal` is neither resolvable nor reachable on port 80 next to one.
   */
  private[gcp] val hostEnvVarName = "GCE_METADATA_HOST"

  /**
   * The base URI for a given `GCE_METADATA_HOST` value.
   *
   * Separate from [[baseUri]], and total, because [[baseUri]] is an object
   * initialiser: raising from here would surface as an
   * `ExceptionInInitializerError` on first touch of this object — from a
   * mistyped environment variable — with the real cause buried. Both an unset
   * variable and one that will not parse yield [[defaultHost]]. `Uri.parse` is
   * lenient enough that the latter is close to unreachable; the fallback is
   * there so that it cannot throw, not because it is expected.
   *
   * A leading scheme is tolerated and dropped. It is not part of the format,
   * but `http://host:port` is the obvious thing to put in a variable whose
   * value ends up in a URL, and prepending a second scheme would otherwise send
   * the request somewhere nobody intended.
   */
  private[gcp] def baseUriForHost(host: Option[String]): Uri = {
    val authority = host
      .map(_.trim)
      .map(_.stripPrefix("http://").stripPrefix("https://"))
      .map(_.stripSuffix("/"))
      .filter(_.nonEmpty)
      .getOrElse(defaultHost)

    Uri
      .parse(s"http://$authority/computeMetadata/v1")
      .getOrElse(uri"http://metadata.google.internal/computeMetadata/v1")
  }

  private[gcp] val baseUri = baseUriForHost(sys.env.get(hostEnvVarName))
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
