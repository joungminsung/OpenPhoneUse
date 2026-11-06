package com.phoneuse.files

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class FileToolException(message: String) : Exception(message)

private const val MAX_FAILED_SHOWN = 10
private const val MAX_TYPES_SHOWN = 30

/**
 * Root-confined file operations. Every path is resolved to its canonical form and must stay inside
 * [root] (symlinks included). There is intentionally no permanent delete: [trash] moves into an
 * app trash folder under the root.
 */
class FileTools(
    root: File,
    private val exif: ExifReader,
    private val trashDirName: String = ".PhoneUseTrash",
    private val now: () -> Long = System::currentTimeMillis,
    private val storagePrefix: String = "/storage/emulated/0",
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private val rootDir: File = root.canonicalFile
    private val trashDir: File = File(rootDir, trashDirName)
    private val mtimeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private val stampFormat = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    fun listDir(path: String, limit: Int = 200, offset: Int = 0): String {
        if (limit < 1) throw FileToolException("limit must be at least 1")
        if (offset < 0) throw FileToolException("offset must not be negative")
        val dir = resolve(path)
        if (!dir.exists()) throw FileToolException("not found: $path")
        if (!dir.isDirectory) throw FileToolException("not a directory: $path")
        val children = safeChildren(dir).sortedWith(
            compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }, { it.name })
        )
        if (children.isEmpty()) return "(empty)"
        val page = children.drop(offset).take(limit)
        if (page.isEmpty()) return "(no more: ${children.size} entries)"
        val lines = page.map { line(it) }.toMutableList()
        val end = offset + page.size
        if (end < children.size) {
            lines += "… ${children.size - end} more of ${children.size}, next offset=$end"
            lines += "all ${children.size} by type: ${typeCounts(children)}"
        }
        return lines.joinToString("\n")
    }

    fun search(query: String, under: String = "", limit: Int = 100): String {
        if (query.isBlank()) throw FileToolException("query is empty")
        val start = resolve(under)
        if (!start.isDirectory) throw FileToolException("not a directory: $under")
        val matcher = nameMatcher(query)
        if (limit < 1) throw FileToolException("limit must be at least 1")
        val hits = ArrayList<File>()
        // Collects at most limit + 1 hits; never descends into symlinked directories.
        fun walk(dir: File) {
            for (child in safeChildren(dir).sortedBy { it.name.lowercase() }) {
                if (hits.size > limit) return
                val isDir = child.isDirectory
                if (isDir && (child.name.startsWith(".") || child == trashDir)) continue
                if (matcher(child.name)) hits += child
                if (isDir && !isLink(child)) walk(child)
            }
        }
        walk(start)
        if (hits.isEmpty()) return "no matches"
        val lines = hits.take(limit).map { display(it) + if (it.isDirectory) "/" else "" }.toMutableList()
        if (hits.size > limit) lines += "… more"
        return lines.joinToString("\n")
    }

    fun info(path: String): String {
        val f = existing(path)
        val sb = StringBuilder()
        sb.append("path: ").append(display(f).ifEmpty { "/" })
        if (f.isDirectory) {
            sb.append("\ntype: directory")
            sb.append("\nentries: ").append(safeChildren(f).size)
        } else {
            val mime = mimeOf(f.name)
            sb.append("\nsize: ").append(f.length())
            sb.append("\nmime: ").append(mime)
            if (mime.startsWith("image/")) {
                exif.read(f)?.let {
                    it.takenAt?.let { t -> sb.append("\ntaken: ").append(t) }
                    it.model?.let { m -> sb.append("\ndevice: ").append(m) }
                }
            }
        }
        sb.append("\nmodified: ").append(formatTime(f.lastModified()))
        return sb.toString()
    }

    fun mkdir(path: String): String {
        val dir = resolve(path)
        if (dir.exists() && !dir.isDirectory) throw FileToolException("already exists as a file: $path")
        if (!dir.isDirectory && !dir.mkdirs()) throw FileToolException("could not create: $path")
        return "created ${display(dir)}/"
    }

    fun move(from: String, to: String): String {
        val src = existingEntry(from)
        guardNotRootOrTrash(src, "move")
        val dest = destFor(src, to)
        guardDest(src, dest)
        moveFile(src, dest)
        return "moved ${display(src)} -> ${display(dest)}"
    }

    /**
     * Moves the loose files directly in [fromDir] whose names match any of [patterns] into [toDir], made if missing.
     * Folders, links and hidden files stay. A taken name gets a " (2)", so nothing is overwritten, and a file that
     * fails does not stop the others.
     */
    fun moveMatching(fromDir: String, patterns: List<String>, toDir: String): String {
        if (patterns.isEmpty() || patterns.any { it.isBlank() }) throw FileToolException("patterns must not be empty")
        val from = resolve(fromDir)
        if (!from.isDirectory) throw FileToolException("not a directory: $fromDir")
        if (isInTrash(from)) throw FileToolException("cannot move out of the trash folder")
        val to = resolve(toDir)
        if (to == from) throw FileToolException("source and destination are the same folder")
        if (isInTrash(to)) throw FileToolException("cannot write into the trash folder")
        if (to.exists() && !to.isDirectory) throw FileToolException("not a folder: $toDir")
        val matchers = patterns.map { nameMatcher(it.trim()) }
        val matched = safeChildren(from)
            .filter { f -> f.isFile && !isLink(f) && !f.name.startsWith(".") && matchers.any { it(f.name) } }
            .sortedBy { it.name.lowercase() }
        if (matched.isEmpty()) return "moved 0: nothing matched in ${displayDir(from)}"
        if (!to.isDirectory && !to.mkdirs()) throw FileToolException("could not create: $toDir")
        var renamed = 0
        val failed = ArrayList<String>()
        for (src in matched) {
            val dest = uniqueIn(to, src.name)
            try {
                moveFile(src, dest)
                if (dest.name != src.name) renamed++
            } catch (e: FileToolException) {
                failed += "${src.name}: ${e.message}"
            }
        }
        val lines = mutableListOf("moved ${matched.size - failed.size} of ${matched.size} to ${displayDir(to)}")
        if (renamed > 0) lines += "kept both under a new name: $renamed"
        lines += failed.take(MAX_FAILED_SHOWN).map { "failed: $it" }
        if (failed.size > MAX_FAILED_SHOWN) lines += "… ${failed.size - MAX_FAILED_SHOWN} more failed"
        return lines.joinToString("\n")
    }

    fun copy(from: String, to: String): String {
        val src = existing(from)
        val dest = destFor(src, to)
        guardDest(src, dest)
        try {
            copyTree(src, dest)
        } catch (e: Exception) {
            if (exists(dest)) deleteTree(dest)
            throw if (e is FileToolException) e else FileToolException("copy failed: ${e.message}")
        }
        return "copied ${display(src)} -> ${display(dest)}"
    }

    fun rename(path: String, newName: String): String {
        val src = existingEntry(path)
        guardNotRootOrTrash(src, "rename")
        if (newName.isBlank() || newName == "." || newName == ".." ||
            newName.contains('/') || newName.contains('\\')
        ) {
            throw FileToolException("invalid name: $newName")
        }
        val dest = File(src.parentFile, newName)
        if (exists(dest)) throw FileToolException("already exists: ${display(dest)}")
        if (!src.renameTo(dest)) throw FileToolException("rename failed: $path")
        return "renamed ${display(src)} -> ${display(dest)}"
    }

    fun trash(paths: List<String>): String {
        if (paths.isEmpty()) throw FileToolException("no paths given")
        val all = paths.map { p ->
            val f = existingEntry(p)
            guardNotRootOrTrash(f, "trash")
            f
        }.distinct()
        // Drop entries nested inside another entry of the same request.
        val sources = all.filter { f -> all.none { o -> o != f && f.path.startsWith(o.path + File.separator) } }
        val stampDir = File(trashDir, stampFormat.format(Instant.ofEpochMilli(now()).atZone(zone)))
        if (!stampDir.isDirectory && !stampDir.mkdirs()) throw FileToolException("could not create trash folder")
        return sources.joinToString("\n") { src ->
            val dest = uniqueIn(stampDir, src.name)
            moveFile(src, dest)
            "${display(src)} -> ${display(dest)}"
        }
    }

    // --- helpers ---

    private fun resolve(path: String): File {
        val p = path.trim()
        val rel = when {
            p == storagePrefix -> ""
            p.startsWith("$storagePrefix/") -> p.substring(storagePrefix.length + 1)
            p.startsWith("/") || p.startsWith("\\") || File(p).isAbsolute ->
                throw FileToolException("path is outside the allowed root: $path")
            else -> p
        }
        val canon = try {
            File(rootDir, rel).canonicalFile
        } catch (e: java.io.IOException) {
            throw FileToolException("invalid path: $path")
        }
        if (!isInside(canon)) throw FileToolException("path is outside the allowed root: $path")
        return canon
    }

    private fun isInside(f: File): Boolean =
        f == rootDir || f.path.startsWith(rootDir.path + File.separator)

    private fun isInTrash(f: File): Boolean =
        f == trashDir || f.path.startsWith(trashDir.path + File.separator)

    private fun existing(path: String): File {
        val f = resolve(path)
        if (!f.exists()) throw FileToolException("not found: $path")
        return f
    }

    /**
     * Resolves [path] to the entry itself without following a final symlink: the parent is
     * canonical (and must be inside the root), the last name is kept as is.
     */
    private fun entry(path: String): File {
        val p = path.trim()
        val rel = when {
            p == storagePrefix -> ""
            p.startsWith("$storagePrefix/") -> p.substring(storagePrefix.length + 1)
            p.startsWith("/") || p.startsWith("\\") || File(p).isAbsolute ->
                throw FileToolException("path is outside the allowed root: $path")
            else -> p
        }
        val base = try {
            File(rootDir, rel).toPath().normalize()
        } catch (e: Exception) {
            throw FileToolException("invalid path: $path")
        }
        if (base == rootDir.toPath()) return rootDir
        val parent = base.parent ?: throw FileToolException("path is outside the allowed root: $path")
        val canonParent = try {
            parent.toFile().canonicalFile
        } catch (e: IOException) {
            throw FileToolException("invalid path: $path")
        }
        if (!isInside(canonParent)) throw FileToolException("path is outside the allowed root: $path")
        return File(canonParent, base.fileName.toString())
    }

    private fun existingEntry(path: String): File {
        val f = entry(path)
        if (!exists(f)) throw FileToolException("not found: $path")
        return f
    }

    private fun exists(f: File): Boolean = Files.exists(f.toPath(), LinkOption.NOFOLLOW_LINKS)

    private fun isLink(f: File): Boolean = Files.isSymbolicLink(f.toPath())

    private fun guardNotRootOrTrash(f: File, verb: String) {
        if (f == rootDir) throw FileToolException("cannot $verb the root folder")
        if (isInTrash(f)) throw FileToolException("cannot $verb the trash folder or its contents")
    }

    private fun destFor(src: File, to: String): File {
        val t = entry(to)
        return if (t.isDirectory) File(resolve(to), src.name) else t
    }

    private fun guardDest(src: File, dest: File) {
        if (exists(dest)) throw FileToolException("already exists: ${display(dest)}")
        if (src.isDirectory && !isLink(src) && (dest == src || dest.path.startsWith(src.path + File.separator))) {
            throw FileToolException("cannot move or copy a folder into itself")
        }
        if (isInTrash(dest)) throw FileToolException("cannot write into the trash folder")
        dest.parentFile?.mkdirs()
    }

    private fun moveFile(src: File, dest: File) {
        if (src.renameTo(dest)) return
        // Cross-filesystem fallback: copy then delete, but never when links are involved.
        if (containsLink(src)) throw FileToolException("cannot move a folder containing links across storage")
        try {
            copyTree(src, dest)
        } catch (e: Exception) {
            if (exists(dest)) deleteTree(dest)
            throw FileToolException("move failed: ${e.message}")
        }
        try {
            deleteTree(src)
        } catch (e: IOException) {
            throw FileToolException("copied but could not fully remove source: ${display(src)}")
        }
    }

    private fun containsLink(src: File): Boolean {
        var found = false
        Files.walkFileTree(src.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (attrs.isSymbolicLink) { found = true; return FileVisitResult.TERMINATE }
                return FileVisitResult.CONTINUE
            }
        })
        return found
    }

    /** Copies a file or folder tree; refuses to copy symlinks inside the tree. */
    private fun copyTree(src: File, dest: File) {
        val srcPath = src.toPath()
        val destPath = dest.toPath()
        Files.walkFileTree(srcPath, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.createDirectories(destPath.resolve(srcPath.relativize(dir).toString()))
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (attrs.isSymbolicLink) throw FileToolException("cannot copy a folder containing links")
                Files.copy(
                    file, destPath.resolve(srcPath.relativize(file).toString()),
                    StandardCopyOption.COPY_ATTRIBUTES,
                )
                return FileVisitResult.CONTINUE
            }
        })
    }

    /** Deletes a tree entry by entry without following links. */
    private fun deleteTree(target: File) {
        Files.walkFileTree(target.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                if (exc != null) throw exc
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    /** Children whose canonical location still lies inside the root (skips escaping symlinks). */
    private fun safeChildren(dir: File): List<File> =
        (dir.listFiles() ?: emptyArray()).filter {
            try { isInside(it.canonicalFile) } catch (e: java.io.IOException) { false }
        }

    private fun uniqueIn(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val dot = name.lastIndexOf('.')
        val (base, ext) = if (dot > 0) name.substring(0, dot) to name.substring(dot) else name to ""
        var n = 2
        while (true) {
            candidate = File(dir, "$base ($n)$ext")
            if (!candidate.exists()) return candidate
            n++
        }
    }

    private fun display(f: File): String =
        f.path.removePrefix(rootDir.path).trimStart(File.separatorChar).replace(File.separatorChar, '/')

    private fun displayDir(f: File): String = display(f).ifEmpty { "/" }

    /** "jpg 120, pdf 30, folders 2": what a long folder holds, so tidying it does not need every page. */
    private fun typeCounts(entries: List<File>): String {
        val counts = entries.groupingBy { f ->
            val dot = f.name.lastIndexOf('.')
            when {
                f.isDirectory -> "folders"
                dot > 0 && dot < f.name.length - 1 -> f.name.substring(dot + 1).lowercase()
                else -> "no extension"
            }
        }.eachCount().entries.sortedWith(compareBy({ -it.value }, { it.key }))
        val shown = counts.take(MAX_TYPES_SHOWN).joinToString(", ") { "${it.key} ${it.value}" }
        return if (counts.size > MAX_TYPES_SHOWN) "$shown, … ${counts.size - MAX_TYPES_SHOWN} more types" else shown
    }

    private fun line(f: File): String =
        if (f.isDirectory) "d ${f.name}/" else "f ${f.name} ${f.length()} ${formatTime(f.lastModified())}"

    private fun formatTime(millis: Long): String = mtimeFormat.format(Instant.ofEpochMilli(millis).atZone(zone))

    private fun nameMatcher(query: String): (String) -> Boolean {
        if (query.contains('*') || query.contains('?')) {
            val regex = Regex(
                query.map {
                    when (it) {
                        '*' -> ".*"
                        '?' -> "."
                        else -> Regex.escape(it.toString())
                    }
                }.joinToString(""),
                RegexOption.IGNORE_CASE,
            )
            return { name -> regex.matches(name) }
        }
        return { name -> name.contains(query, ignoreCase = true) }
    }

    private fun mimeOf(name: String): String =
        when (name.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "heic" -> "image/heic"
            "heif" -> "image/heif"
            "bmp" -> "image/bmp"
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "mov" -> "video/quicktime"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "wav" -> "audio/wav"
            "ogg" -> "audio/ogg"
            "pdf" -> "application/pdf"
            "zip" -> "application/zip"
            "apk" -> "application/vnd.android.package-archive"
            "json" -> "application/json"
            "txt", "log", "md" -> "text/plain"
            "csv" -> "text/csv"
            "html", "htm" -> "text/html"
            "xml" -> "text/xml"
            "doc" -> "application/msword"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xls" -> "application/vnd.ms-excel"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "ppt" -> "application/vnd.ms-powerpoint"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            else -> "application/octet-stream"
        }
}
