package com.phoneuse.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.time.ZoneId
import java.time.ZonedDateTime

class FileToolsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val zone = ZoneId.of("UTC")
    private val fixedNow = ZonedDateTime.of(2026, 3, 4, 5, 6, 7, 0, zone).toInstant().toEpochMilli()
    private lateinit var root: File
    private lateinit var tools: FileTools

    private val fakeExif = object : ExifReader {
        override fun read(file: File) = ExifInfo("2025:01:02 03:04:05", "Pixel 9")
    }

    @Before fun setUp() {
        root = tmp.newFolder("root")
        tools = FileTools(root, fakeExif, now = { fixedNow }, zone = zone)
    }

    private fun file(rel: String, text: String = "hi", mtime: Long = fixedNow): File =
        File(root, rel).apply {
            parentFile!!.mkdirs(); writeText(text); setLastModified(mtime)
        }

    private fun fails(msg: String, block: () -> Unit) {
        try {
            block()
            fail("expected FileToolException containing '$msg'")
        } catch (e: FileToolException) {
            assertTrue("was: ${e.message}", e.message!!.contains(msg))
        }
    }

    @Test fun listsFoldersFirstSorted() {
        file("b.txt"); file("a.txt"); File(root, "zdir").mkdirs(); File(root, "Adir").mkdirs()
        val lines = tools.listDir("").lines()
        assertEquals("d Adir/", lines[0])
        assertEquals("d zdir/", lines[1])
        assertEquals("f a.txt 2 2026-03-04 05:06", lines[2])
        assertEquals("f b.txt 2 2026-03-04 05:06", lines[3])
    }

    @Test fun listTruncatesWithMoreCount() {
        repeat(5) { file("f$it.txt") }
        val lines = tools.listDir("", limit = 3).lines()
        assertEquals(5, lines.size)
        assertEquals("… 2 more of 5, next offset=3", lines[3])
        assertEquals("all 5 by type: txt 5", lines[4])
    }

    @Test fun listPagesThroughEveryEntryWithOffset() {
        repeat(5) { file("f$it.txt") }
        val pages = listOf(0, 2, 4).map { tools.listDir("", limit = 2, offset = it).lines().filter { l -> l.startsWith("f ") } }
        assertEquals(listOf("f0.txt", "f1.txt", "f2.txt", "f3.txt", "f4.txt"), pages.flatten().map { it.split(" ")[1] })
    }

    @Test fun listOffsetPastTheEndSaysSo() {
        file("a.txt")
        assertEquals("(no more: 1 entries)", tools.listDir("", offset = 1))
        fails("offset") { tools.listDir("", offset = -1) }
    }

    @Test fun listSummaryCountsTypesMostCommonFirst() {
        repeat(3) { file("a$it.JPG") }
        file("b.pdf")
        file("noext")
        File(root, "dir").mkdirs()
        val last = tools.listDir("", limit = 1).lines().last()
        assertEquals("all 6 by type: jpg 3, folders 1, no extension 1, pdf 1", last)
    }

    @Test fun moveMatchingMovesLooseMatchesAndLeavesTheRest() {
        file("Download/a.jpg")
        file("Download/B.JPG")
        file("Download/c.pdf")
        file("Download/.pending-1-d.jpg")
        file("Download/keep/e.jpg")
        File(root, "Download/album.jpg").mkdirs()
        val r = tools.moveMatching("Download", listOf("*.jpg"), "Download/사진")
        assertEquals("moved 2 of 2 to Download/사진", r)
        assertTrue(File(root, "Download/사진/a.jpg").isFile)
        assertTrue(File(root, "Download/사진/B.JPG").isFile)
        assertFalse(File(root, "Download/a.jpg").exists())
        assertTrue(File(root, "Download/c.pdf").isFile)
        assertTrue(File(root, "Download/.pending-1-d.jpg").isFile)
        assertTrue(File(root, "Download/keep/e.jpg").isFile)
        assertTrue(File(root, "Download/album.jpg").isDirectory)
    }

    @Test fun moveMatchingAcceptsStoragePathsAndSeveralPatterns() {
        file("Download/a.jpg")
        file("Download/b.png")
        val r = tools.moveMatching("/storage/emulated/0/Download", listOf("*.jpg", "*.png"), "/storage/emulated/0/Pictures/x")
        assertEquals("moved 2 of 2 to Pictures/x", r)
    }

    @Test fun moveMatchingHandlesHundredsInOneCall() {
        repeat(500) { file("Download/f$it.pdf") }
        assertEquals("moved 500 of 500 to Download/문서", tools.moveMatching("Download", listOf("*.pdf"), "Download/문서"))
        assertEquals(500, File(root, "Download/문서").list()!!.size)
    }

    @Test fun moveMatchingNeverOverwritesATakenName() {
        file("Download/a.jpg", "new")
        file("Download/사진/a.jpg", "old")
        val r = tools.moveMatching("Download", listOf("*.jpg"), "Download/사진")
        assertEquals("moved 1 of 1 to Download/사진\nkept both under a new name: 1", r)
        assertEquals("old", File(root, "Download/사진/a.jpg").readText())
        assertEquals("new", File(root, "Download/사진/a (2).jpg").readText())
    }

    @Test fun moveMatchingKeepsGoingPastAFailure() {
        // A taken 255-character name cannot grow a " (2)", so that one file fails on any file system.
        val long = "x".repeat(251) + ".jpg"
        file("Download/$long", "new")
        file("Download/사진/$long", "old")
        file("Download/z.jpg")
        val lines = tools.moveMatching("Download", listOf("*.jpg"), "Download/사진").lines()
        assertEquals("moved 1 of 2 to Download/사진", lines[0])
        assertTrue(lines.toString(), lines.last().startsWith("failed: $long"))
        assertTrue(File(root, "Download/사진/z.jpg").isFile)
        assertEquals("new", File(root, "Download/$long").readText())
        assertEquals("old", File(root, "Download/사진/$long").readText())
    }

    @Test fun moveMatchingSaysWhenNothingMatched() {
        file("Download/a.txt")
        assertEquals("moved 0: nothing matched in Download", tools.moveMatching("Download", listOf("*.jpg"), "Download/사진"))
        assertFalse(File(root, "Download/사진").exists())
    }

    @Test fun moveMatchingRefusesBadFolders() {
        file("Download/a.jpg")
        file("note.txt")
        tools.trash(listOf("note.txt"))
        fails("patterns") { tools.moveMatching("Download", emptyList(), "x") }
        fails("patterns") { tools.moveMatching("Download", listOf(" "), "x") }
        fails("not a directory") { tools.moveMatching("nope", listOf("*"), "x") }
        fails("same folder") { tools.moveMatching("Download", listOf("*"), "Download") }
        fails("trash") { tools.moveMatching("Download", listOf("*"), ".PhoneUseTrash/x") }
        fails("trash") { tools.moveMatching(".PhoneUseTrash", listOf("*"), "x") }
        fails("outside") { tools.moveMatching("Download", listOf("*"), "/etc") }
        fails("not a folder") { tools.moveMatching("Download", listOf("*"), "note2.txt".also { file(it) }) }
        assertTrue(File(root, "Download/a.jpg").isFile)
    }

    @Test fun searchMatchesGlobAndSkipsTrash() {
        file("docs/Report.PDF"); file("docs/notes.txt"); file(".hidden/report.txt")
        file(".PhoneUseTrash/20260101-000000/report.pdf")
        val sub = tools.search("report")
        assertTrue(sub, sub.contains("docs/Report.PDF"))
        assertFalse(sub, sub.contains(".hidden"))
        assertFalse(sub, sub.contains("PhoneUseTrash"))
        val glob = tools.search("*.txt")
        assertTrue(glob, glob.contains("docs/notes.txt"))
        assertFalse(glob, glob.contains("Report.PDF"))
        assertTrue(tools.search("n?tes.TXT", under = "docs").contains("docs/notes.txt"))
    }

    @Test fun infoIncludesExifForImages() {
        file("p.jpg", "abc"); file("t.txt")
        val img = tools.info("p.jpg")
        assertTrue(img, img.contains("image/jpeg"))
        assertTrue(img, img.contains("2025:01:02 03:04:05"))
        assertTrue(img, img.contains("Pixel 9"))
        assertTrue(img, img.contains("2026-03-04 05:06"))
        val txt = tools.info("t.txt")
        assertTrue(txt, txt.contains("text/plain"))
        assertFalse(txt, txt.contains("Pixel 9"))
    }

    @Test fun moveIntoExistingFolder() {
        file("a.txt"); File(root, "dir").mkdirs()
        tools.move("a.txt", "dir")
        assertFalse(File(root, "a.txt").exists())
        assertTrue(File(root, "dir/a.txt").exists())
    }

    @Test fun moveRefusesOverwrite() {
        file("a.txt"); file("b.txt")
        fails("already exists") { tools.move("a.txt", "b.txt") }
        assertTrue(File(root, "a.txt").exists())
    }

    @Test fun copyFile() {
        file("a.txt", "data")
        tools.copy("a.txt", "c.txt")
        assertEquals("data", File(root, "c.txt").readText())
        assertTrue(File(root, "a.txt").exists())
        fails("already exists") { tools.copy("a.txt", "c.txt") }
    }

    @Test fun copyDirectoryRecursively() {
        file("d/x.txt", "1"); file("d/sub/y.txt", "2")
        tools.copy("d", "e")
        assertEquals("1", File(root, "e/x.txt").readText())
        assertEquals("2", File(root, "e/sub/y.txt").readText())
        assertTrue(File(root, "d/sub/y.txt").exists())
    }

    @Test fun renameKeepsParent() {
        file("d/a.txt")
        tools.rename("d/a.txt", "b.txt")
        assertTrue(File(root, "d/b.txt").exists())
        assertFalse(File(root, "d/a.txt").exists())
        file("d/c.txt")
        fails("already exists") { tools.rename("d/b.txt", "c.txt") }
        fails("name") { tools.rename("d/b.txt", "x/y.txt") }
    }

    @Test fun mkdirCreatesNested() {
        tools.mkdir("a/b/c")
        assertTrue(File(root, "a/b/c").isDirectory)
    }

    @Test fun rejectsPathEscape() {
        fails("outside") { tools.listDir("../x") }
        fails("outside") { tools.info("/etc/hosts") }
        fails("outside") { tools.mkdir("a/../../y") }
    }

    @Test fun rejectsSymlinkEscape() {
        val outside = tmp.newFolder("outside")
        File(outside, "secret.txt").writeText("s")
        val link = File(root, "link")
        try {
            Files.createSymbolicLink(link.toPath(), outside.toPath())
        } catch (e: Exception) {
            assumeTrue("symlinks unavailable: $e", false)
        }
        fails("outside") { tools.listDir("link") }
        fails("outside") { tools.info("link/secret.txt") }
    }

    @Test fun trashMovesWithTimestampAndDedupes() {
        file("a.txt", "1"); file("d/a.txt", "2")
        val r1 = tools.trash(listOf("a.txt"))
        val dir = File(root, ".PhoneUseTrash/20260304-050607")
        assertTrue(File(dir, "a.txt").exists())
        assertFalse(File(root, "a.txt").exists())
        assertTrue(r1, r1.contains("a.txt") && r1.contains(".PhoneUseTrash/20260304-050607/a.txt"))
        tools.trash(listOf("d/a.txt"))
        assertEquals("2", File(dir, "a (2).txt").readText())
    }

    @Test fun trashRefusesTrashDir() {
        file("a.txt"); tools.trash(listOf("a.txt"))
        fails("trash") { tools.trash(listOf(".PhoneUseTrash")) }
        fails("trash") { tools.trash(listOf(".PhoneUseTrash/20260304-050607/a.txt")) }
    }

    @Test fun rejectsSiblingWithRootPrefix() {
        File(tmp.root, "rootbc").mkdirs()
        fails("outside") { tools.listDir("../rootbc") }
        fails("outside") { tools.mkdir("../rootbc/x") }
    }

    @Test fun moveFolderIntoItselfRefused() {
        file("d/x.txt"); File(root, "d/sub").mkdirs()
        fails("into itself") { tools.move("d", "d/sub") }
        fails("into itself") { tools.copy("d", "d/sub") }
        assertTrue(File(root, "d/x.txt").exists())
    }

    @Test fun rootCannotBeMovedOrTrashed() {
        file("a.txt")
        fails("root") { tools.move("", "x") }
        fails("root") { tools.trash(listOf("")) }
        fails("root") { tools.rename("", "x") }
        assertTrue(File(root, "a.txt").exists())
    }

    @Test fun searchStopsAtLimit() {
        repeat(10) { file("m/hit$it.txt") }
        val lines = tools.search("hit", limit = 3).lines()
        assertEquals(4, lines.size)
        assertEquals("… more", lines[3])
    }

    @Test fun trashHandlesDuplicateAndNestedPaths() {
        file("d/x.txt"); file("a.txt")
        tools.trash(listOf("a.txt", "a.txt", "d", "d/x.txt"))
        val dir = File(root, ".PhoneUseTrash/20260304-050607")
        assertTrue(File(dir, "a.txt").exists())
        assertTrue(File(dir, "d/x.txt").exists())
        assertFalse(File(dir, "a (2).txt").exists())
    }

    @Test fun emptyFolderAndBadLimit() {
        File(root, "e").mkdirs()
        assertEquals("(empty)", tools.listDir("e"))
        fails("limit") { tools.listDir("", limit = 0) }
    }

    @Test fun searchDoesNotLoopOnSymlinkToAncestor() {
        file("d/hit.txt")
        try {
            Files.createSymbolicLink(File(root, "d/loop").toPath(), root.toPath())
        } catch (e: Exception) {
            assumeTrue("symlinks unavailable: $e", false)
        }
        val r = tools.search("hit")
        assertEquals(1, r.lines().size)
    }

    @Test fun trashingSymlinkMovesLinkNotTarget() {
        file("real/x.txt")
        try {
            Files.createSymbolicLink(File(root, "lnk").toPath(), File(root, "real").toPath())
        } catch (e: Exception) {
            assumeTrue("symlinks unavailable: $e", false)
        }
        tools.trash(listOf("lnk"))
        assertTrue(File(root, "real/x.txt").exists())
        assertFalse(Files.exists(File(root, "lnk").toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
    }

    @Test fun acceptsAbsoluteStoragePath() {
        file("DCIM/a.jpg")
        val t = FileTools(root, fakeExif, now = { fixedNow }, storagePrefix = "/storage/emulated/0", zone = zone)
        assertTrue(t.listDir("/storage/emulated/0/DCIM").contains("a.jpg"))
        assertTrue(t.listDir("/storage/emulated/0").contains("DCIM/"))
        fails("outside") { t.listDir("/storage/emulated/1/x") }
    }
}
