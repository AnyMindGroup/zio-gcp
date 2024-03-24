package com.anymindgroup.gcp.auth

import zio.Duration

final case class AccessToken(token: String, expiresIn: Duration)
