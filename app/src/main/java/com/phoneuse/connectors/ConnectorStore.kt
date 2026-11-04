package com.phoneuse.connectors

import com.phoneuse.auth.OAuthTokenSet
import com.phoneuse.mcp.McpServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * The Google connector's settings: the user's own "Desktop app" OAuth client and, once connected, the tokens and
 * the account's address.
 */
@Serializable
data class GoogleConfig(
    val clientId: String,
    val clientSecret: String? = null,
    val tokens: OAuthTokenSet? = null,
    val email: String? = null,
) {
    val connected: Boolean get() = tokens != null

    override fun toString() = "GoogleConfig(clientId=$clientId, email=$email, connected=$connected, secrets=<redacted>)"
}

/**
 * Connector settings, each kept as one encrypted secret ([read]/[write] are SettingsStore.readSecret/writeSecret,
 * which seal values with the Keystore). Tokens therefore never touch plain storage or logs.
 */
class ConnectorStore(
    private val read: suspend (String) -> String?,
    private val write: suspend (String, String?) -> Unit,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private var loaded = false

    private val _servers = MutableStateFlow<List<McpServer>>(emptyList())
    val servers: StateFlow<List<McpServer>> = _servers.asStateFlow()

    private val _google = MutableStateFlow<GoogleConfig?>(null)
    val google: StateFlow<GoogleConfig?> = _google.asStateFlow()

    suspend fun load() = mutex.withLock { loadLocked() }

    private suspend fun loadLocked() {
        if (loaded) return
        _servers.value = read(KEY_SERVERS)?.let {
            runCatching { json.decodeFromString(ListSerializer(McpServer.serializer()), it) }.getOrNull()
        }.orEmpty()
        _google.value = read(KEY_GOOGLE)?.let { runCatching { json.decodeFromString(GoogleConfig.serializer(), it) }.getOrNull() }
        loaded = true
    }

    suspend fun server(id: String): McpServer? {
        load()
        return _servers.value.firstOrNull { it.id == id }
    }

    /** Applies [change] to the server list and saves it. */
    suspend fun updateServers(change: (List<McpServer>) -> List<McpServer>) = mutex.withLock {
        loadLocked()
        val next = change(_servers.value)
        write(KEY_SERVERS, if (next.isEmpty()) null else json.encodeToString(ListSerializer(McpServer.serializer()), next))
        _servers.value = next
    }

    suspend fun updateServer(id: String, change: (McpServer) -> McpServer) =
        updateServers { list -> list.map { if (it.id == id) change(it) else it } }

    /** null removes the Google connector entirely. */
    suspend fun updateGoogle(change: (GoogleConfig?) -> GoogleConfig?) = mutex.withLock {
        loadLocked()
        val next = change(_google.value)
        write(KEY_GOOGLE, next?.let { json.encodeToString(GoogleConfig.serializer(), it) })
        _google.value = next
    }

    private companion object {
        const val KEY_SERVERS = "mcp_servers"
        const val KEY_GOOGLE = "google_connector"
    }
}
