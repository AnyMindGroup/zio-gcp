package com.anymindgroup.pubsub.http

import zio.Schedule

import com.anymindgroup.gcp.auth.TokenProvider

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
