package dev.paperplane.cli.server

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime

object ServerSync {

    /**
     * Syncs all runtime state from source server to target server.
     * Uses incremental sync (timestamp + size) to skip unchanged files.
     * Copies everything except lock files and CLI state, then patches the port in server.properties.
     * The dev plugin jar and companion jar are skipped (deployed fresh after sync).
     */
    fun syncServerState(sourceDir: File, targetDir: File, targetPort: Int, devPluginJarName: String) {
        targetDir.mkdirs()
        val srcChildren = (sourceDir.listFiles() ?: emptyArray()).associateBy { it.name }
        val dstChildren = (targetDir.listFiles() ?: emptyArray()).associateBy { it.name }

        // Remove files in target that don't exist in source (respecting skip rules)
        for ((name, dstChild) in dstChildren) {
            if (name !in srcChildren) {
                if (dstChild.isDirectory) deleteDir(dstChild) else dstChild.delete()
            }
        }

        for ((name, child) in srcChildren) {
            if (child.name.endsWith(".lock")) continue
            if (child.name == ".paperplane") continue // CLI state, not server data

            val dst = File(targetDir, child.name)
            if (child.name == "plugins") {
                syncPlugins(child, dst, devPluginJarName)
            } else if (child.isDirectory) {
                incrementalSyncDir(child, dst)
            } else {
                copyIfChanged(child, dst)
            }
        }
        // Patch port — the only config difference between blue and green
        val props = File(targetDir, "server.properties")
        if (props.exists()) {
            props.writeText(props.readText().replace(Regex("server-port=\\d+"), "server-port=$targetPort"))
        }
    }

    private fun syncPlugins(srcPlugins: File, dstPlugins: File, devPluginJarName: String) {
        dstPlugins.mkdirs()
        val srcChildren = (srcPlugins.listFiles() ?: emptyArray()).associateBy { it.name }
        val dstChildren = (dstPlugins.listFiles() ?: emptyArray()).associateBy { it.name }

        // Remove files in target that don't exist in source (respecting skip rules)
        for ((name, dstChild) in dstChildren) {
            if (name == devPluginJarName || name == "paperplane-companion.jar") continue
            if (name !in srcChildren) {
                if (dstChild.isDirectory) deleteDir(dstChild) else dstChild.delete()
            }
        }

        for ((name, child) in srcChildren) {
            if (child.isFile && (child.name == devPluginJarName || child.name == "paperplane-companion.jar")) continue
            val dst = File(dstPlugins, name)
            if (child.isDirectory) {
                incrementalSyncDir(child, dst)
            } else {
                copyIfChanged(child, dst)
            }
        }
    }

    private fun incrementalSyncDir(src: File, dst: File) {
        dst.mkdirs()
        val srcChildren = (src.listFiles() ?: emptyArray()).associateBy { it.name }
        val dstChildren = (dst.listFiles() ?: emptyArray()).associateBy { it.name }

        // Remove files in dst that don't exist in src
        for ((name, dstChild) in dstChildren) {
            if (name !in srcChildren) {
                if (dstChild.isDirectory) deleteDir(dstChild) else dstChild.delete()
            }
        }

        // Copy/update from src to dst
        for ((name, srcChild) in srcChildren) {
            if (srcChild.name.endsWith(".lock")) continue
            val dstChild = File(dst, name)
            if (srcChild.isDirectory) {
                incrementalSyncDir(srcChild, dstChild)
            } else {
                copyIfChanged(srcChild, dstChild)
            }
        }
    }

    private fun copyIfChanged(src: File, dst: File) {
        if (!dst.exists() || isFileChanged(src, dst)) {
            Files.copy(
                src.toPath(), dst.toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES
            )
        }
    }

    private fun isFileChanged(src: File, dst: File): Boolean {
        if (src.length() != dst.length()) return true
        // Use NIO FileTime for higher resolution than File.lastModified() (millis)
        val srcTime: FileTime = Files.getLastModifiedTime(src.toPath())
        val dstTime: FileTime = Files.getLastModifiedTime(dst.toPath())
        return srcTime != dstTime
    }

    private fun deleteDir(dir: File) {
        if (!dir.exists()) return
        val files = dir.listFiles()
        if (files != null) {
            for (child in files) {
                if (child.isDirectory) deleteDir(child) else child.delete()
            }
        }
        dir.delete()
    }
}
