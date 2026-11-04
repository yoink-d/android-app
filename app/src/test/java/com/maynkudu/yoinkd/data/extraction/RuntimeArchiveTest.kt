package com.maynkudu.yoinkd.data.extraction

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class RuntimeArchiveTest {
    private lateinit var root: File
    @Before fun setUp() { root = Files.createTempDirectory("yoinkd-package-").toFile() }
    @After fun tearDown() { root.deleteRecursively() }

    private fun archive(vararg files: Pair<String, String>): File = File(root, "package.zip").also { file ->
        ZipOutputStream(file.outputStream()).use { zip -> files.forEach { (name, content) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(content.toByteArray()); zip.closeEntry()
        } }
    }

    @Test fun relativeSymlinkChainsResolveToTheInstalledLibrary() {
        val payload = archive("usr/lib/library.so.1" to "library", "usr/lib/library.so" to "library.so.1",
            "usr/lib/alias.so" to "library.so")
        val destination = File(root, "installed")
        installRuntimeArchive(payload, destination, mapOf("usr/lib/library.so" to "library.so.1", "usr/lib/alias.so" to "library.so"))
        assertTrue(Files.isSymbolicLink(File(destination, "usr/lib/alias.so").toPath()))
        assertEquals("library", File(destination, "usr/lib/alias.so").readText())
    }

    @Test fun traversalCannotWriteOutsideTheInstallationFolder() {
        val payload = archive("../outside.txt" to "unsafe")
        assertThrows(IllegalArgumentException::class.java) { installRuntimeArchive(payload, File(root, "installed"), emptyMap()) }
        assertFalse(File(root, "outside.txt").exists())
    }

    @Test fun absoluteLinksAreRejectedBeforeCreation() {
        val payload = archive("usr/lib/link" to "/etc/passwd")
        assertThrows(IllegalArgumentException::class.java) {
            installRuntimeArchive(payload, File(root, "installed"), mapOf("usr/lib/link" to "/etc/passwd"))
        }
        assertFalse(Files.isSymbolicLink(File(root, "installed/usr/lib/link").toPath()))
    }

    @Test fun parentTraversingLinksAreRejected() {
        val payload = archive("usr/lib/link" to "../../../outside")
        assertThrows(IllegalArgumentException::class.java) {
            installRuntimeArchive(payload, File(root, "installed"), mapOf("usr/lib/link" to "../../../outside"))
        }
    }

    @Test fun cyclicLinksCannotBecomeAnInstalledRuntime() {
        val payload = archive("usr/lib/one" to "two", "usr/lib/two" to "one")
        assertThrows(IllegalArgumentException::class.java) {
            installRuntimeArchive(payload, File(root, "installed"), mapOf("usr/lib/one" to "two", "usr/lib/two" to "one"))
        }
    }

    @Test fun aLinkMustMatchThePinnedManifestAndResolveToAFile() {
        val mismatch = archive("usr/lib/link" to "unexpected")
        assertThrows(IllegalArgumentException::class.java) {
            installRuntimeArchive(mismatch, File(root, "mismatch"), mapOf("usr/lib/link" to "expected"))
        }
        val broken = archive("usr/lib/link" to "missing.so")
        assertThrows(IllegalArgumentException::class.java) {
            installRuntimeArchive(broken, File(root, "broken"), mapOf("usr/lib/link" to "missing.so"))
        }
    }
}
