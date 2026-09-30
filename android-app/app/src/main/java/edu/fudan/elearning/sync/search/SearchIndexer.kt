package edu.fudan.elearning.sync.search

import android.content.Context
import edu.fudan.elearning.sync.data.FileItem
import edu.fudan.elearning.sync.data.Repo
import kotlinx.coroutines.ensureActive
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * 搜索索引的写入协调：下载完成后即时索引，同步收尾时限量回填老库。
 *
 * 设计约束（与桌面端一致）：
 * - 任何抽取/写库失败都只索引文件名，**绝不能让搜索建立失败影响同步**；
 * - 回填可被同步的取消信号协作中断；
 * - 回填限量（每轮 100 个），大库分多轮完成。
 */
object SearchIndexer {

    private const val BACKFILL_BATCH = 100

    /**
     * 抽取 [file] 的文本并写入索引（文件名始终会被索引）。
     * 在调用方的协程上下文中执行（调用方应处于 IO 线程）。
     */
    suspend fun indexFile(context: Context, repo: Repo, file: FileItem) {
        runCatching {
            val localPath = file.localPath.takeIf { it.isNotEmpty() } ?: return@runCatching
            val local = File(localPath)
            val text = if (local.isFile) TextExtractor.extractText(context, local) else null
            repo.upsertFileIndex(file.fileId, file.name, text)
        }
    }

    /**
     * 给已下载但未索引的文件补索引（老库迁移路径）。
     * 每轮限量、可取消；[onProgress] 用于把进度报给同步界面。
     * @return 本轮实际建立索引的数量
     */
    suspend fun backfill(
        context: Context,
        repo: Repo,
        onProgress: (message: String) -> Unit = {}
    ): Int {
        val pending = runCatching { repo.unindexedDownloadedFiles(BACKFILL_BATCH) }
            .getOrDefault(emptyList())
        if (pending.isEmpty()) return 0
        onProgress("为 ${pending.size} 个已下载文件建立内容索引…")
        var done = 0
        for (file in pending) {
            coroutineContext.ensureActive()
            indexFile(context, repo, file)
            done += 1
        }
        if (done > 0) onProgress("内容索引已建立：$done 个文件")
        return done
    }
}
