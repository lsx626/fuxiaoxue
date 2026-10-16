package edu.fudan.elearning.sync.sync

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

/** Bounded workers, streaming results, and one serial database consumer. */
internal suspend fun <T, R> processDownloads(
    tasks: List<T>,
    concurrency: Int,
    download: suspend (T) -> R,
    consume: suspend (T, R) -> Unit
) = coroutineScope {
    require(concurrency > 0)
    val work = Channel<T>(concurrency)
    val results = Channel<Pair<T, R>>(concurrency)
    try {
        launch {
            try { tasks.forEach { work.send(it) } } finally { work.close() }
        }
        val workers = List(minOf(concurrency, tasks.size)) {
            launch { for (task in work) results.send(task to download(task)) }
        }
        launch { workers.joinAll(); results.close() }
        for ((task, result) in results) consume(task, result)
    } finally {
        work.cancel()
        results.cancel()
    }
}
