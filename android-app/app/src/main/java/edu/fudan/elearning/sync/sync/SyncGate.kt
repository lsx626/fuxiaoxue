package edu.fudan.elearning.sync.sync

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 进程级同步互斥闸门。
 *
 * 手动同步（`AppViewModel.sync()`）与后台 Worker 都在同一进程内运行，必须互斥： 否则两个同步会同时写数据库、重复下载同一文件，甚至互相覆盖 `.part`。
 * 后到者直接跳过（返回 null），而不是排队堆积多次同步。
 */
object SyncGate {
    private val mutex = Mutex()
    private val _busy = kotlinx.coroutines.flow.MutableStateFlow(false)
    val busy: kotlinx.coroutines.flow.StateFlow<Boolean> = _busy

    val isBusy: Boolean
        get() = mutex.isLocked

    suspend fun <T> runWaiting(block: suspend () -> T): T =
        mutex.withLock {
            _busy.value = true
            try {
                block()
            } finally {
                _busy.value = false
            }
        }

    /** 拿到闸门则执行 [block]；已有同步在跑时返回 null。 */
    suspend fun <T> runOrSkip(block: suspend () -> T): T? {
        if (!mutex.tryLock()) return null
        _busy.value = true
        return try {
            block()
        } finally {
            _busy.value = false
            mutex.unlock()
        }
    }
}
