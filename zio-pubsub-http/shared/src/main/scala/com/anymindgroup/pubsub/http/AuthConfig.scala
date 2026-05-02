package com.anymindgroup.pubsub.http

import com.anymindgroup.gcp.auth.TokenProvider

import zio.Schedule

case class AuthConfig(
  lookupComputeMetadataFirst: Boolean,
  tokenRefreshRetrySchedule: Schedule[Any, Any, Any],
  tokenRefreshAtExpirationPercent: Double,
)

object AuthConfig:
  val default: AuthConfig = AuthConfig(
    lookupComputeMetadataFirst = true,
    tokenRefreshRetrySchedule = TokenProvider.defaults.refreshRetrySchedule,
    tokenRefreshAtExpirationPercent = TokenProvider.defaults.refreshAtExpirationPercent,
  )
