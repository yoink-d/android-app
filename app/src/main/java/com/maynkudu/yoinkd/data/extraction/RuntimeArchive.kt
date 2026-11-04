package com.maynkudu.yoinkd.data.extraction

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.util.zip.ZipFile

/** Extracts regular files first, then creates only declared, internal relative symlinks. */
internal fun installRuntimeArchive(archive: File, destination: File, links: Map<String, String>,
    checkCancellation: () -> Unit = {}) {
    check(destination.mkdirs() || destination.isDirectory)
    val root = destination.canonicalFile
    val names = mutableSetOf<String>()
    var expanded = 0L
    fun member(path: String): File {
        require(path.isNotEmpty() && !path.startsWith('/') && '\\' !in path && '\u0000' !in path &&
            path.split('/').none { it == ".." || it == "." }) { "The media package contains an unsafe path." }
        val target = File(root, path).canonicalFile
        require(target.path.startsWith(root.path + "/")) { "The media package leaves its installation folder." }
        return target
    }
    ZipFile(archive).use { zip ->
        val entries = zip.entries().toList()
        require(entries.size <= 30_000) { "The media package contains too many files." }
        for (entry in entries) {
            checkCancellation()
            val name = entry.name.trimEnd('/')
            val target = member(name)
            require(names.add(name)) { "The media package contains duplicate files." }
            val link = links[entry.name]
            if (link != null) {
                require(!entry.isDirectory && entry.size in 1..4_096) { "The media package contains an invalid link." }
                val content = zip.getInputStream(entry).use { it.readBytes() }.decodeToString()
                require(content == link) { "The media package link does not match its manifest." }
            } else if (entry.isDirectory) check(target.mkdirs() || target.isDirectory)
            else {
                check(target.parentFile!!.mkdirs() || target.parentFile!!.isDirectory)
                check(target.createNewFile()) { "The media package overwrites an existing file." }
                zip.getInputStream(entry).use { input ->
                    FileOutputStream(target).use { output ->
                        val buffer = ByteArray(65_536)
                        while (true) {
                            checkCancellation()
                            val count = input.read(buffer)
                            if (count < 0) break
                            expanded += count
                            require(expanded <= 512_000_000) { "The expanded media package is too large." }
                            output.write(buffer, 0, count)
                        }
                        output.fd.sync()
                    }
                }
            }
        }
    }
    for ((path, relative) in links) {
        require(path in names && relative.isNotEmpty() && !relative.startsWith('/') &&
            '\\' !in relative && '\u0000' !in relative && relative.split('/').none { it == ".." }) {
            "The media package contains an unsafe link."
        }
        var resolved = path
        val visited = mutableSetOf<String>()
        while (resolved in links) {
            require(visited.add(resolved)) { "The media package contains a cyclic link." }
            val target = links.getValue(resolved)
            require(!target.startsWith('/') && '\\' !in target && '\u0000' !in target &&
                target.split('/').none { it == ".." }) { "The media package contains an unsafe link." }
            resolved = (Paths.get(resolved).parent ?: Paths.get("")).resolve(target).normalize().toString()
            member(resolved)
        }
        require(member(resolved).exists()) { "The media package contains a broken link." }
    }
    for ((path, relative) in links) {
        checkCancellation()
        val target = member(path)
        check(target.parentFile!!.mkdirs() || target.parentFile!!.isDirectory)
        Files.createSymbolicLink(target.toPath(), Paths.get(relative))
    }
}
