package com.anymindgroup.gcp.storage

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.format.{DateTimeFormatter, DateTimeFormatterBuilder}
import java.time.temporal.ChronoField
import java.time.{Instant, ZoneOffset}

import sttp.model.Uri.QuerySegmentEncoding
import sttp.model.{MediaType, Method, QueryParams}

// https://cloud.google.com/storage/docs/authentication/canonical-requests
private[storage] case class V4CanonicalRequest(
  payloadPlain: String,
  stringToSign: String,
  canonicalQueryParams: QueryParams,
)

private[storage] class V4CanonicalRequestBuilder(
  dateTimeFormatter: DateTimeFormatter
) {
  // https://cloud.google.com/storage/docs/authentication/canonical-requests
  def toCanonicalRequest(
    method: Method,
    timestamp: Instant,
    resourcePath: String,
    contentType: Option[MediaType],
    bucket: String,
    serviceAccountEmail: String,
    signAlgorithm: V4SignAlgorithm,
    expiresInSeconds: V4SignatureExpiration,
  ): Either[Throwable, V4CanonicalRequest] = {
    // Sort all headers by header name using a lexicographical sort by code point value.
    // Make all header names lowercase.
    // https://cloud.google.com/storage/docs/authentication/canonical-requests#about-headers
    val canonicalHeaders =
      contentType
        .map(ct => "content-type" -> ct.toString())
        .toList
        .appended("host" -> "storage.googleapis.com")

    val signedHeadersString    = canonicalHeaders.map((k, _) => k).mkString(";")
    val canonicalHeadersString = canonicalHeaders.map((k, v) => s"$k:$v").mkString("\n")

    // The parameters in the query string must be sorted by name using a lexicographical sort by code point value.
    // https://cloud.google.com/storage/docs/authentication/canonical-requests#about-query-strings
    def canonicalQueryParams(credential: String, dateTime: String) = QueryParams
      .fromSeq(
        List(
          "X-Goog-Algorithm"     -> signAlgorithm.toString(),
          "X-Goog-Credential"    -> s"$serviceAccountEmail/$credential",
          "X-Goog-Date"          -> dateTime,
          "X-Goog-Expires"       -> expiresInSeconds.toString(),
          "X-Goog-SignedHeaders" -> signedHeadersString,
        )
      )

    val odt             = timestamp.atOffset(ZoneOffset.UTC)
    val dateTime        = odt.format(dateTimeFormatter)
    val date            = odt.toLocalDate.format(DateTimeFormatter.BASIC_ISO_DATE)
    val credentialScope = s"$date/auto/storage/goog4_request"
    val canonicalQueryP = canonicalQueryParams(credential = credentialScope, dateTime = dateTime)
    val canonicalQueryStr = canonicalQueryP.toString(
      keyEncoding = QuerySegmentEncoding.Standard,
      valueEncoding = QuerySegmentEncoding.All,
      includeBoundary = false,
    )

    // https://cloud.google.com/storage/docs/authentication/canonical-requests#request-structure
    //
    // HTTP_VERB
    // PATH_TO_RESOURCE
    // CANONICAL_QUERY_STRING
    // CANONICAL_HEADERS
    //
    // SIGNED_HEADERS
    // PAYLOAD
    val payload = s"""|${method.method}
                      |/$bucket/${resourcePath.stripPrefix("/")}
                      |$canonicalQueryStr
                      |$canonicalHeadersString
                      |
                      |$signedHeadersString
                      |UNSIGNED-PAYLOAD""".stripMargin

    for {
      payloadHash <- try {
                       Right {
                         val sha256Digest = MessageDigest.getInstance("SHA-256")
                         val hashBytes    = sha256Digest.digest(payload.getBytes(StandardCharsets.UTF_8))
                         hashBytes.map("%02x".format(_)).mkString
                       }
                     } catch {
                       case e: Throwable => Left(e)
                     }
    } yield V4CanonicalRequest(
      payloadPlain = payload,
      // https://cloud.google.com/storage/docs/authentication/signatures#string-to-sign-example
      stringToSign = s"""|${signAlgorithm.toString()}
                         |$dateTime
                         |$credentialScope
                         |$payloadHash""".stripMargin,
      canonicalQueryParams = canonicalQueryP,
    )
  }
}

private[storage] object V4CanonicalRequestBuilder:
  def apply(): V4CanonicalRequestBuilder =
    // in format YYYYMMDD'T'HHMMSS'Z' e.g. 20191201T190859Z
    new V4CanonicalRequestBuilder(
      new DateTimeFormatterBuilder()
        .appendValue(ChronoField.YEAR, 4)
        .appendValue(ChronoField.MONTH_OF_YEAR, 2)
        .appendValue(ChronoField.DAY_OF_MONTH, 2)
        .appendLiteral('T')
        .appendValue(ChronoField.HOUR_OF_DAY, 2)
        .appendValue(ChronoField.MINUTE_OF_HOUR, 2)
        .appendValue(ChronoField.SECOND_OF_MINUTE, 2)
        .appendLiteral('Z')
        .toFormatter()
    )
