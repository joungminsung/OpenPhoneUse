package com.phoneuse.agent

import com.phoneuse.files.FileToolException
import com.phoneuse.files.FileTools
import com.phoneuse.model.ToolSpec
import com.phoneuse.risk.RiskCategory
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * The file tools, mapped onto [FileTools]; `trash_files` goes through the DELETE confirmation first. Offered even
 * without the all-files permission ([files] null): a call then says the permission is needed.
 */
internal class FileModule(private val files: FileTools?, private val gate: UserGate) : ToolModule {
    override val specs: List<ToolSpec> get() = SPECS

    override suspend fun run(name: String, args: JsonObject, context: ToolContext): ToolResult {
        val tools = files ?: return fail("모든 파일 접근 권한이 필요해요")
        return try {
            when (name) {
                "list_dir" -> ok(tools.listDir(args.requireStr("path"), args.int("limit") ?: DEFAULT_LIST_LIMIT))
                "search_files" ->
                    ok(tools.search(args.requireStr("query"), args.str("under") ?: "", args.int("limit") ?: DEFAULT_SEARCH_LIMIT))
                "file_info" -> ok(tools.info(args.requireStr("path")))
                "mkdir" -> ok(tools.mkdir(args.requireStr("path")))
                "move_file" -> ok(tools.move(args.requireStr("from"), args.requireStr("to")))
                "copy_file" -> ok(tools.copy(args.requireStr("from"), args.requireStr("to")))
                "rename_file" -> ok(tools.rename(args.requireStr("path"), args.requireStr("new_name")))
                "trash_files" -> {
                    val paths = (args["paths"] as? JsonArray)
                        ?.map { (it as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.content ?: throw IllegalArgumentException("paths must be strings") }
                        ?: throw IllegalArgumentException("paths required")
                    if (paths.isEmpty()) throw IllegalArgumentException("paths is empty")
                    if (!gate.confirm(RiskCategory.DELETE, trashSummary(paths))) {
                        ToolResult("DECLINED by user", null, null, null)
                    } else {
                        ok(tools.trash(paths))
                    }
                }
                else -> fail("unknown tool $name")
            }
        } catch (e: FileToolException) {
            fail(e.message ?: "file error")
        } catch (e: IllegalArgumentException) {
            fail("invalid arguments: ${e.message}")
        }
    }

    private fun trashSummary(paths: List<String>): String {
        val shown = paths.take(MAX_SHOWN).joinToString(", ") { clip(it) }
        val more = if (paths.size > MAX_SHOWN) "(외 ${paths.size - MAX_SHOWN}개)" else ""
        return "휴지통으로 이동: $shown$more"
    }

    /** One line, at most [MAX_PATH_CHARS] code points; a longer path keeps its tail behind "…". */
    private fun clip(path: String): String {
        val t = path.replace(Regex("\\s+"), " ").trim()
        val n = t.codePointCount(0, t.length)
        return if (n <= MAX_PATH_CHARS) t else "…" + t.substring(t.offsetByCodePoints(0, n - (MAX_PATH_CHARS - 1)))
    }

    private fun ok(output: String) = ToolResult(output, null, null, null)

    private fun fail(reason: String) = ToolResult("ERROR: $reason", null, null, null)

    companion object {
        const val MAX_SHOWN = 5
        const val MAX_PATH_CHARS = 60
        const val DEFAULT_LIST_LIMIT = 200
        const val DEFAULT_SEARCH_LIMIT = 100

        private val filePath = prop("string", "Absolute path under /storage/emulated/0.")

        val SPECS: List<ToolSpec> = listOf(
            ToolSpec(
                "list_dir",
                "List a directory's entries. Paths are absolute under /storage/emulated/0.",
                schema(
                    required = listOf("path"),
                    props = mapOf("path" to prop("string", "Directory path."), "limit" to prop("integer", "Max entries (default 200).")),
                ),
            ),
            ToolSpec(
                "search_files",
                "Search files by name under a directory.",
                schema(
                    required = listOf("query"),
                    props = mapOf(
                        "query" to prop("string", "Case-insensitive name part."),
                        "under" to prop("string", "Directory to search in (default: storage root)."),
                        "limit" to prop("integer", "Max results (default 100)."),
                    ),
                ),
            ),
            ToolSpec("file_info", "Size, dates and metadata of a file or directory.", schema(required = listOf("path"), props = mapOf("path" to filePath))),
            ToolSpec("mkdir", "Create a directory (with parents).", schema(required = listOf("path"), props = mapOf("path" to filePath))),
            ToolSpec(
                "move_file",
                "Move a file or directory.",
                schema(required = listOf("from", "to"), props = mapOf("from" to filePath, "to" to filePath)),
            ),
            ToolSpec(
                "copy_file",
                "Copy a file or directory.",
                schema(required = listOf("from", "to"), props = mapOf("from" to filePath, "to" to filePath)),
            ),
            ToolSpec(
                "rename_file",
                "Rename a file or directory within its folder.",
                schema(required = listOf("path", "new_name"), props = mapOf("path" to filePath, "new_name" to prop("string", "New name only, no slashes."))),
            ),
            ToolSpec(
                "trash_files",
                "Move files to the trash (recoverable). The user is asked to confirm.",
                schema(
                    required = listOf("paths"),
                    props = mapOf(
                        "paths" to buildJsonObject {
                            put("type", "array")
                            put("description", "Paths to move to the trash.")
                            putJsonObject("items") { put("type", "string") }
                        },
                    ),
                ),
            ),
        )
    }
}
