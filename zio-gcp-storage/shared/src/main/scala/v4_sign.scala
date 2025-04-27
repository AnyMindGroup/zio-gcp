package com.anymindgroup.gcp.storage

// https://cloud.google.com/storage/docs/authentication/canonical-requests#required-query-parameters

// for X-Goog-Algorithm header
enum V4SignAlgorithm:
  case `GOOG4-RSA-SHA256`, `GOOG4-HMAC-SHA256`

// for X-Goog-Expires header
// The longest expiration value is 604800 seconds (7 days)
opaque type V4SignatureExpiration = Int
object V4SignatureExpiration:
  def inSecondsUnsafe(v: Int): V4SignatureExpiration = v

  inline def inSeconds(inline v: Int): V4SignatureExpiration =
    if v > 604800 then compiletime.error("The longest expiration value is 604800 seconds") else v

  extension (v: V4SignatureExpiration) def toSeconds: Int = v
