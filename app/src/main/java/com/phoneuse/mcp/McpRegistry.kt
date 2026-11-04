package com.phoneuse.mcp

import com.phoneuse.i18n.tr
import com.phoneuse.model.ToolSpec
import com.phoneuse.risk.RiskCategory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.security.MessageDigest

/** An MCP tool as the model sees it: [exposedName] (`mcp__<server>__<tool>`), its spec, and the confirmation it needs. */
data class ExposedTool(
    val exposedName: String,
    val server: McpServer,
    val tool: McpTool,
    val spec: ToolSpec,
    /** Non-null when the tool's name or description says it sends, deletes or pays: asked before every call. */
    val risk: RiskCategory?,
)

/**
 * Turns connected servers into model tools. Tool lists are cached per server for [ttlMs]; one client (and so one
 * MCP session) is kept per server. A server that fails or takes longer than [timeoutMs] is left out of the run.
 */
class McpRegistry(
    private val clientFor: (McpServer) -> McpClient,
    private val ttlMs: Long = 10 * 60_000L,
    private val timeoutMs: Long = 10_000L,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private class Entry(val key: String, val client: McpClient, var tools: List<ExposedTool>? = null, var at: Long = 0)

    private val lock = Mutex()
    private val entries = HashMap<String, Entry>()

    /** The client for [server] (shared with [tools]). */
    suspend fun client(server: McpServer): McpClient = entry(server).client

    /** All enabled servers' tools, listed in parallel; failures are reported to [onError] and skipped. */
    suspend fun tools(servers: List<McpServer>, onError: (McpServer, Exception) -> Unit = { _, _ -> }): List<ExposedTool> =
        coroutineScope {
            servers.filter { it.enabled }.map { server ->
                async {
                    try {
                        withTimeoutOrNull(timeoutMs) { toolsOf(server) } ?: run {
                            onError(server, McpException(tr("Timed out", "시간 초과")))
                            emptyList()
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        onError(server, e)
                        emptyList()
                    }
                }
            }.awaitAll().flatten()
        }.let { dedupe(it) }

    /** Lists [server]'s tools now (no cache), for the connectors screen's test button. */
    suspend fun refresh(server: McpServer): List<ExposedTool> {
        val e = entry(server)
        e.tools = null
        return toolsOf(server)
    }

    /** Drops [serverId]'s client and cached tools; returns the client so its session can be closed. */
    fun forget(serverId: String): McpClient? = synchronized(entries) { entries.remove(serverId)?.client }

    private suspend fun toolsOf(server: McpServer): List<ExposedTool> {
        val e = entry(server)
        lock.withLock { e.tools?.takeIf { now() - e.at < ttlMs }?.let { return it } }
        val listed = expose(server, e.client.listTools())
        lock.withLock {
            e.tools = listed
            e.at = now()
        }
        return listed
    }

    private fun entry(server: McpServer): Entry = synchronized(entries) {
        val key = server.url
        entries[server.id]?.takeIf { it.key == key } ?: Entry(key, clientFor(server)).also { entries[server.id] = it }
    }

    companion object {
        const val MAX_TOOLS_PER_SERVER = 40
        private const val MAX_NAME = 64
        const val PREFIX = "mcp__"

        /** Whether [toolName] is an MCP tool: its description and schema were written by the server. */
        fun isMcpTool(toolName: String): Boolean = toolName.startsWith(PREFIX)
        private val NOT_NAME = Regex("[^A-Za-z0-9_]+")
        private val UNDERSCORES = Regex("_{2,}")
        private val SKIP_LABELS = setOf("mcp", "www", "api", "app", "server")

        /** One name part: runs of other characters become one underscore, no leading/trailing underscores. */
        fun part(raw: String, fallback: String): String =
            raw.replace(NOT_NAME, "_").replace(UNDERSCORES, "_").trim('_').ifEmpty { fallback }

        /** `mcp__<server>__<tool>`, only letters, digits and underscores, at most 64 characters. */
        fun toolName(server: String, tool: String): String {
            val full = PREFIX + part(server, "server") + "__" + part(tool, "tool")
            if (full.length <= MAX_NAME) return full
            // Long names keep their start and get a short hash of the whole, so two long names stay distinct.
            val hash = MessageDigest.getInstance("SHA-256").digest(full.toByteArray()).joinToString("") { "%02x".format(it) }.take(6)
            return full.take(MAX_NAME - 7) + "_" + hash
        }

        /** A short server name from its address: "https://mcp.notion.com/mcp" -> "notion". */
        fun serverName(url: String): String {
            val host = url.toHttpUrlOrNull()?.host ?: return "server"
            if (host.all { it.isDigit() || it == '.' || it == ':' } || host == "localhost") return "server"
            val labels = host.split('.').filter { it.isNotEmpty() }
            val meaningful = labels.dropLast(1).filter { it !in SKIP_LABELS && it !in COUNTRY_SECOND_LEVEL }
            return part(meaningful.firstOrNull() ?: labels.firstOrNull() ?: "server", "server").lowercase()
        }

        private val COUNTRY_SECOND_LEVEL = setOf("co", "com", "or", "go", "ne", "ac", "net", "org")

        /** [server]'s first [MAX_TOOLS_PER_SERVER] tools with unique names. */
        fun expose(server: McpServer, tools: List<McpTool>): List<ExposedTool> {
            val seen = HashSet<String>()
            val out = ArrayList<ExposedTool>()
            for (t in tools) {
                if (out.size >= MAX_TOOLS_PER_SERVER) break
                val name = toolName(server.name, t.name)
                if (!seen.add(name)) continue
                val risk = McpRisk.classify(t.name, t.description, t.readOnly, t.destructive)
                val note = if (risk != null) " (사용자 확인 후 실행)" else ""
                val params = schema(t.inputSchema)
                val omitted = if (params == null) " (인수 설명이 너무 길어 생략됨)" else ""
                val description = "[${server.name} 커넥터$note] " + clean(t.description).take(MAX_DESCRIPTION).ifEmpty { t.name } + omitted
                out += ExposedTool(name, server, t, ToolSpec(name, description, params ?: OPEN_SCHEMA), risk)
            }
            return out
        }

        /** Two servers may expose the same name; the later one is dropped. */
        private fun dedupe(tools: List<ExposedTool>): List<ExposedTool> {
            val seen = HashSet<String>()
            return tools.filter { seen.add(it.exposedName) }
        }

        /** Server-written text reaches every turn (and the server may change it any time): kept short. */
        const val MAX_DESCRIPTION = 300

        /** Largest serialized argument schema per tool; it is sent with every model turn. */
        const val MAX_SCHEMA_CHARS = 4_096

        /** Longest nested `description` kept in a schema; longer ones are dropped. */
        const val MAX_SCHEMA_DESCRIPTION = 300

        private val OPEN_SCHEMA = JsonObject(mapOf("type" to JsonPrimitive("object"), "properties" to JsonObject(emptyMap())))

        /** Control characters out, whitespace runs collapsed. */
        fun clean(text: String): String = text.replace(Regex("[\\p{Cntrl}\\s]+"), " ").trim()

        /**
         * The model API wants an object schema with properties; `$schema` is dropped, and so is any nested
         * `description` longer than [MAX_SCHEMA_DESCRIPTION]. Still over [MAX_SCHEMA_CHARS], all nested descriptions
         * go; still over, null (the tool is offered with an open schema). Server text there reaches every turn.
         */
        fun schema(raw: JsonObject): JsonObject? {
            val map = LinkedHashMap(raw)
            map.remove("\$schema")
            map["type"] = JsonPrimitive("object")
            if (map["properties"] !is JsonObject) map["properties"] = JsonObject(emptyMap())
            val trimmed = strip(JsonObject(map), MAX_SCHEMA_DESCRIPTION) as JsonObject
            if (trimmed.toString().length <= MAX_SCHEMA_CHARS) return trimmed
            val bare = strip(trimmed, 0) as JsonObject
            return bare.takeIf { it.toString().length <= MAX_SCHEMA_CHARS }
        }

        /** Drops `description` strings longer than [max] (all of them at 0) at every level; also `$comment`/`examples`. */
        private fun strip(e: JsonElement, max: Int): JsonElement = when (e) {
            is JsonObject -> JsonObject(
                e.filter { (k, v) ->
                    k != "\$comment" && k != "examples" &&
                        !(k == "description" && v is JsonPrimitive && v.isString && (max == 0 || v.content.length > max))
                }.mapValues { (k, v) -> if (k == "properties" || k != "description") strip(v, max) else v },
            )
            is JsonArray -> JsonArray(e.map { strip(it, max) })
            else -> e
        }
    }
}

/**
 * Words in an MCP tool's name or description that mean it pays, grants access, deletes or overwrites, sends, or
 * changes something. Such a tool shows a confirmation card first (MONEY, SECURITY and DELETE always; SEND and
 * CHANGE unless Settings turned that off). Name words match whole tokens (`send_message`, `deletePage`);
 * description words match as words, so "posts" or "in order to" do not count.
 */
object McpRisk {
    private val moneyNames = setOf("pay", "payment", "payments", "purchase", "buy", "checkout", "charge", "refund", "transfer", "order", "subscribe")
    private val securityNames = setOf(
        "permission", "permissions", "grant", "revoke", "token", "tokens", "apikey", "password", "passwords", "credential",
        "credentials", "secret", "secrets", "role", "roles", "admin", "member", "members", "collaborator", "collaborators",
        "acl", "sharing", "visibility",
    )
    private val deleteNames = setOf(
        "delete", "remove", "trash", "erase", "destroy", "purge", "clear", "unlink",
        // Overwrites lose the old content as surely as a delete.
        "replace", "overwrite", "merge", "push", "archive", "reset", "wipe",
    )
    private val sendNames = setOf("send", "reply", "forward", "publish", "post", "submit", "invite", "comment", "share", "notify", "tweet")
    private val changeNames = setOf("update", "edit", "modify", "patch", "set", "move", "rename", "create", "add", "write", "upload", "insert", "append")

    /** Names that only read; an unannotated tool named like this is not asked about. */
    private val readNames = setOf("get", "list", "search", "fetch", "read", "find", "query", "lookup", "describe", "view", "show", "retrieve", "count")

    private val moneyText = Regex("""(?i)\b(pay|pays|payment|payments|purchase|purchases|checkout|refund|charges? (?:a|the) card)\b|결제|송금|구매|이체""")
    private val securityText = Regex(
        """(?i)\b(permissions?|grants? access|api[ _-]?keys?|access tokens?|passwords?|credentials?|admin rights|share[sd]? with)\b|권한|비밀번호|관리자""",
    )
    private val deleteText = Regex("""(?i)\b(delete|deletes|deleting|remove|removes|trash|trashes|erase|erases|permanently|purge|purges|overwrites?|replaces? (?:the |all )?(?:content|contents))\b|삭제|지우|휴지통|덮어""")
    private val sendText = Regex("""(?i)\b(send|sends|sending|publish|publishes|reply|replies)\b|보내|전송|발송|게시|답장""")

    /**
     * [readOnly]/[destructive] are the tool's MCP annotations. `destructiveHint: true` always asks (as DELETE unless a
     * money, security or send word says more). `readOnlyHint: true` is the server's own claim, so it never lowers
     * risk: it only drops delete and security words found in the description (a reader that mentions "deleted
     * items"); a word in the name (`delete_page`) still asks, as do money and send words. Per the MCP spec a tool
     * that is not read-only and has no `destructiveHint` may be destructive, so it is asked about as CHANGE unless
     * its name only reads (`get_page`); `destructiveHint: false` means it only adds.
     */
    fun classify(name: String, description: String, readOnly: Boolean? = null, destructive: Boolean? = null): RiskCategory? {
        val tokens = tokens(name)
        val joined = tokens.joinToString("")
        // The name goes along for its Korean words ("메일_보내기"); English name words are matched as tokens.
        val text = "$name $description"
        // A name that only reads ("get_permissions", "notion-fetch"): no write verb in it.
        val reads = tokens.any { it in readNames } && tokens.none { it in changeNames || it in deleteNames || it in sendNames }
        return when {
            tokens.any { it in moneyNames } || moneyText.containsMatchIn(text) -> RiskCategory.MONEY
            !reads && (tokens.any { it in securityNames } || "apikey" in joined || "accesstoken" in joined) -> RiskCategory.SECURITY
            readOnly != true && !reads && securityText.containsMatchIn(text) -> RiskCategory.SECURITY
            destructive != true && tokens.any { it in deleteNames } -> RiskCategory.DELETE
            destructive != true && readOnly != true && deleteText.containsMatchIn(text) -> RiskCategory.DELETE
            tokens.any { it in sendNames } || sendText.containsMatchIn(text) -> RiskCategory.SEND
            destructive == true -> RiskCategory.DELETE
            readOnly == true -> null
            tokens.any { it in changeNames } -> RiskCategory.CHANGE
            destructive == false || reads -> null
            else -> RiskCategory.CHANGE
        }
    }

    /** "notion-create-pages" -> [notion, create, pages]; "createPayment" -> [create, payment]. */
    private fun tokens(name: String): List<String> =
        name.replace(Regex("([a-z0-9])([A-Z])"), "$1 $2").lowercase().split(Regex("[^a-z0-9가-힣]+")).filter { it.isNotEmpty() }
}
