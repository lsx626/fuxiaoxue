package edu.fudan.elearning.sync.sync

import android.content.Context
import edu.fudan.elearning.sync.data.FileItem
import java.io.File

/** Only this application's download trees may be changed by storage actions. */
class LocalStorage(context: Context) {
    val root = File(context.getExternalFilesDir(null) ?: context.filesDir, "elearning")
    private val roots =
        listOfNotNull(context.getExternalFilesDir(null), context.filesDir).map {
            File(it, "elearning")
        }

    fun requireManaged(file: File): File {
        val target = file.canonicalFile
        require(roots.any { target.path.startsWith(it.canonicalPath + File.separator) }) {
            "文件路径不在应用资料目录内，已取消操作"
        }
        return target
    }

    fun deleteLocal(record: FileItem) {
        if (record.localPath.isBlank()) return
        val file = requireManaged(File(record.localPath))
        val part = DownloadPlan.partFile(file)
        check(!file.exists() || (file.isFile && file.delete())) { "文件删除失败，记录已保留" }
        check(!part.exists() || (part.isFile && part.delete())) { "下载断点删除失败，请重试" }
    }

    fun usage(): StorageUsage {
        var bytes = 0L
        var partBytes = 0L
        var count = 0
        for (tree in roots.distinctBy { it.absolutePath }) {
            if (!tree.exists()) continue
            tree
                .walkTopDown()
                .onEnter { !java.nio.file.Files.isSymbolicLink(it.toPath()) }
                .forEach { file ->
                    if (file.isFile && !java.nio.file.Files.isSymbolicLink(file.toPath())) {
                        bytes += file.length()
                        if (DownloadPlan.isPartFile(file)) partBytes += file.length() else count++
                    }
                }
        }
        return StorageUsage(bytes, partBytes, count)
    }
}

data class StorageUsage(val bytes: Long = 0, val partialBytes: Long = 0, val files: Int = 0)
