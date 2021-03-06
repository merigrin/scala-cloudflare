package com.dwolla.cloudflare.domain.dto.accounts

case class UserDTO (
  id: String,
  first_name: Option[String],
  last_name: Option[String],
  email: String,
  two_factor_authentication_enabled: Boolean
)
