package com.phoneuse.connectors

import com.phoneuse.i18n.tr
import com.phoneuse.agent.ConnectorTools
import com.phoneuse.agent.ToolResult
import com.phoneuse.agent.UserGate
import com.phoneuse.auth.AuthException
import com.phoneuse.mcp.ExposedTool
import com.phoneuse.mcp.McpClient
import com.phoneuse.mcp.McpException
import com.phoneuse.mcp.McpOAuth
import com.phoneuse.mcp.McpRegistry
import com.phoneuse.mcp.McpServer
import com.phoneuse.model.ToolSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import java.util.UUID

/**
 * The connectors: MCP servers the user added and the Google connector. [forRun] gives a run its connector tools
 * (none when nothing is connected). [log] gets problems without any token values.
 */
class Connectors(
    private val http: OkHttpClient,
    val store: ConnectorStore,
    private val log: (String) -> Unit = {},
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private val oauth = McpOAuth(http, now)
    private val refreshLock = Mutex()
    val google = GoogleAuth(http, store, now = now)
    private val googleApi = GoogleApi(http, token = { google.accessToken() })
    private val googleTools = GoogleTools(googleApi)
    val registry = McpRegistry(clientFor = { server -> McpClient(http, server.url, token = { tokenFor(server.id) }) })

    /** This run's connector tools, or null when none is connected. Servers that do not answer are skipped. */
    suspend fun forRun(): ConnectorTools? {
        store.load()
        val servers = store.servers.value.filter { it.enabled }
        val googleOn = store.google.value?.connected == true
        if (servers.isEmpty() && !googleOn) return null
        val mcp = registry.tools(servers) { server, e -> log("MCP ${server.name}: ${e.message}") }
        if (mcp.isEmpty() && !googleOn) return null
        return RunTools(mcp, if (googleOn) googleTools else null)
    }

    /** Adds a server (by URL, optionally a pasted bearer token) and checks it answers; returns its tool count. */
    suspend fun addServer(url: String, token: String?, preset: String? = null, name: String? = null): Pair<McpServer, Int> {
        val clean = url.trim()
        if (!isAllowedUrl(clean)) throw McpException(tr("The address must start with https://", "주소는 https://로 시작해야 해요"))
        store.load()
        val taken = store.servers.value.map { it.name }.toSet()
        val base = McpRegistry.part(name ?: preset ?: McpRegistry.serverName(clean), "server").lowercase().take(24)
        var unique = base
        var n = 2
        while (unique in taken) unique = "${base}_${n++}"
        val server = McpServer(UUID.randomUUID().toString(), unique, clean, preset = preset, token = token?.trim()?.takeIf { it.isNotEmpty() })
        store.updateServers { it + server }
        // Anything but "login needed" (a 401) takes the half-added server back out, cancellation included.
        val count = try {
            registry.refresh(server).size
        } catch (e: McpException) {
            if (e.status == 401) {
                -1
            } else {
                withContext(NonCancellable) { removeServer(server.id) }
                throw e
            }
        } catch (e: Throwable) {
            withContext(NonCancellable) { removeServer(server.id) }
            throw e
        }
        return server to count
    }

    /** OAuth login for a server that answered 401; then lists its tools. */
    suspend fun login(serverId: String, openBrowser: (String) -> Unit): Int {
        val server = store.server(serverId) ?: throw McpException(tr("Server not found", "서버를 찾을 수 없어요"))
        val probe = McpClient(http, server.url)
        val metadata = try {
            probe.listTools()
            null
        } catch (e: McpException) {
            e.resourceMetadata
        } finally {
            withContext(NonCancellable) { probe.close() }
        }
        val state = oauth.login(server.url, metadata, openBrowser)
        store.updateServer(serverId) { it.copy(oauth = state, token = null) }
        registry.forget(serverId)?.close()
        return registry.refresh(store.server(serverId)!!).size
    }

    suspend fun setToken(serverId: String, token: String?) {
        store.updateServer(serverId) { it.copy(token = token?.trim()?.takeIf { t -> t.isNotEmpty() }, oauth = null) }
        registry.forget(serverId)?.close()
    }

    suspend fun setEnabled(serverId: String, enabled: Boolean) = store.updateServer(serverId) { it.copy(enabled = enabled) }

    /** Removes the server and ends its MCP session (HTTP DELETE, best effort). */
    suspend fun removeServer(serverId: String) {
        store.updateServers { list -> list.filterNot { it.id == serverId } }
        registry.forget(serverId)?.close()
    }

    suspend fun testServer(serverId: String): Int {
        val server = store.server(serverId) ?: throw McpException(tr("Server not found", "서버를 찾을 수 없어요"))
        return registry.refresh(server).size
    }

    suspend fun connectGoogle(clientId: String, clientSecret: String?, openBrowser: (String) -> Unit) {
        google.connect(clientId, clientSecret, openBrowser) { access ->
            GoogleApi(http, token = { access }).profileEmail()
        }
    }

    /** The bearer for [serverId]: a pasted token, or the OAuth access token, refreshed when it is about to expire. */
    private suspend fun tokenFor(serverId: String): String? {
        val server = store.server(serverId) ?: return null
        server.token?.let { return it }
        val state = server.oauth ?: return null
        if (!state.tokens.expiresSoon(now())) return state.tokens.accessToken
        return refreshLock.withLock {
            val current = store.server(serverId)?.oauth ?: return@withLock null
            if (!current.tokens.expiresSoon(now())) return@withLock current.tokens.accessToken
            // A failed refresh never falls back to the expired token: the user is told to log in again.
            try {
                val fresh = oauth.refresh(server.url, current)
                store.updateServer(serverId) { it.copy(oauth = fresh) }
                fresh.tokens.accessToken
            } catch (e: AuthException) {
                log("MCP ${server.name}: token refresh failed (${e.status})")
                val status = e.status
                if (status == null || status >= 500) throw McpException(tr("Couldn't refresh the ${server.name} token. Please try again in a moment", "${server.name} 토큰을 갱신하지 못했어요. 잠시 뒤 다시 해 주세요"))
                throw McpException(tr("You need to sign in to ${server.name} again (Connectors › Manage)", "${server.name}에 다시 로그인해야 해요 (커넥터 › 관리)"), 401)
            }
        }
    }

    /** One run's view: MCP tools by exposed name, plus the Google tools when connected. */
    private inner class RunTools(mcp: List<ExposedTool>, private val google: GoogleTools?) : ConnectorTools {
        private val byName = mcp.associateBy { it.exposedName }
        override val specs: List<ToolSpec> = mcp.map { it.spec } + google?.specs.orEmpty()

        override fun handles(name: String): Boolean = name in byName || (google != null && name in google.names)

        override suspend fun call(name: String, args: JsonObject, gate: UserGate): ToolResult {
            byName[name]?.let { return callMcp(it, args, gate) }
            return google?.call(name, args, gate) ?: fail("unknown tool $name")
        }

        private suspend fun callMcp(tool: ExposedTool, args: JsonObject, gate: UserGate): ToolResult {
            tool.risk?.let { category ->
                // Server-chosen names go through the same cleaning as tool names, so the card cannot be dressed up.
                val toolName = McpRegistry.part(tool.tool.name, "tool").take(NAME_PREVIEW)
                if (!gate.confirm(category, "${tool.server.name} · $toolName: ${argsPreview(args)}")) {
                    return ToolResult("CANCELLED by user", null, null, null)
                }
            }
            return try {
                val result = registry.client(tool.server).callTool(tool.tool.name, args)
                ToolResult(if (result.isError) "ERROR: ${result.text}" else result.text.ifEmpty { "OK" }, null, null, null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e.message ?: e.toString())
            }
        }

        private fun fail(reason: String) = ToolResult("ERROR: $reason", null, null, null)
    }

    companion object {
        /** Long enough for a whole message or page edit; past it the card says how much is left out. */
        const val ARGS_PREVIEW = 4_000
        private const val NAME_PREVIEW = 60

        /**
         * What the confirm card shows of an MCP call: every top-level argument name first (so no field, such as an
         * extra recipient, hides behind the cut), then the arguments themselves, cut at [ARGS_PREVIEW] with the
         * number of characters left out.
         */
        fun argsPreview(args: JsonObject): String {
            val keys = args.keys.joinToString(", ") { McpRegistry.clean(it).take(NAME_PREVIEW) }
            val all = McpRegistry.clean(args.toString())
            val body = if (all.length > ARGS_PREVIEW) {
                all.take(ARGS_PREVIEW) + tr("… (${all.length - ARGS_PREVIEW} more characters)", "… (${all.length - ARGS_PREVIEW}자 더 있음)")
            } else {
                all
            }
            return if (keys.isEmpty()) body else tr("[fields: $keys] $body", "[항목: $keys] $body")
        }

        /** MCP servers are reached over https only (the app allows no cleartext traffic). */
        fun isAllowedUrl(url: String): Boolean {
            val u = url.trim().toHttpUrlOrNull() ?: return false
            return u.isHttps && u.host.isNotEmpty() && u.username.isEmpty() && u.password.isEmpty()
        }
    }
}
