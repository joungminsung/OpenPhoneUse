package com.phoneuse.mcp

import com.phoneuse.i18n.tr
import kotlinx.serialization.Serializable

/**
 * A connected MCP server. [name] is the short name in tool names (`mcp__<name>__<tool>`). [token] is a pasted
 * bearer token; [oauth] the client and tokens from an OAuth login. Both are only ever stored encrypted.
 * [preset] marks a server added from a board preset ("notion").
 */
@Serializable
data class McpServer(
    val id: String,
    val name: String,
    val url: String,
    val enabled: Boolean = true,
    val preset: String? = null,
    val token: String? = null,
    val oauth: McpOAuthState? = null,
) {
    override fun toString() = "McpServer(id=$id, name=$name, url=$url, enabled=$enabled, auth=${if (token != null || oauth != null) "<set>" else "none"})"
}

/** The board's account presets that are MCP servers. */
enum class McpPreset(val key: String, val title: String, private val subtitleEn: String, private val subtitleKo: String, val url: String?) {
    NOTION("notion", "Notion", "Find notes · Add to-dos", "메모 찾기 · 할 일 추가", "https://mcp.notion.com/mcp"),
    OUTLOOK("outlook", "Outlook", "Work mail · Calendar", "회사 메일 · 일정", null),
    SPOTIFY("spotify", "Spotify", "Playback · Playlists", "재생 · 플레이리스트", null),
    ;

    val subtitle: String get() = tr(subtitleEn, subtitleKo)
}
