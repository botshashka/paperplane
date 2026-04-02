package dev.paperplane.cli.server

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

object ServerSync {

    /**
     * Syncs all runtime state from source server to target server.
     * Copies everything except lock files and CLI state, then patches the port in server.properties.
     * The dev plugin jar and overlay jar are skipped (deployed fresh after sync).
     */
    fun syncServerState(sourceDir: File, targetDir: File, targetPort: Int, devPluginJarName: String) {
        targetDir.mkdirs()
        for (child in sourceDir.listFiles() ?: emptyArray()) {
            if (child.name.endsWith(".lock")) continue
            if (child.name == ".paperplane") continue // CLI state, not server data

            val dst = File(targetDir, child.name)
            if (child.name == "plugins") {
                syncPlugins(child, dst, devPluginJarName)
            } else if (child.isDirectory) {
                deleteDir(dst)
                copyDirSkippingLocks(child, dst)
            } else {
                Files.copy(child.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING)
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
        for (child in srcPlugins.listFiles() ?: emptyArray()) {
            if (child.isFile && (child.name == devPluginJarName || child.name == "paperplane-overlay.jar")) continue
            val dst = File(dstPlugins, child.name)
            if (child.isDirectory) {
                deleteDir(dst)
                copyDirSkippingLocks(child, dst)
            } else {
                Files.copy(child.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    private fun copyDirSkippingLocks(src: File, dst: File) {
        dst.mkdirs()
        for (child in src.listFiles() ?: emptyArray()) {
            if (child.name.endsWith(".lock")) continue
            val target = File(dst, child.name)
            if (child.isDirectory) {
                copyDirSkippingLocks(child, target)
            } else {
                Files.copy(child.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
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
