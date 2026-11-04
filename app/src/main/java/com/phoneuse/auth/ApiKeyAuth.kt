package com.phoneuse.auth

import com.phoneuse.i18n.tr
import com.phoneuse.model.ModelAuth

class ApiKeyAuth(private val key: String) : ModelAuth {
    override val baseUrl = "https://api.openai.com/v1/"
    override val unauthorizedMessage = tr("Check your API key", "API 키를 확인해 주세요")
    override suspend fun headers() = mapOf("Authorization" to "Bearer $key")
    override suspend fun onUnauthorized() = false
}
