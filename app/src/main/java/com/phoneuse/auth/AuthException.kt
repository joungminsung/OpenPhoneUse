package com.phoneuse.auth

/**
 * Login or token failure. [status] is the HTTP code from the token endpoint, or null (network, parsing).
 * [code] is the token endpoint's error code when it gave one (e.g. invalid_grant).
 */
class AuthException(message: String, val status: Int? = null, val code: String? = null) : Exception(message)
