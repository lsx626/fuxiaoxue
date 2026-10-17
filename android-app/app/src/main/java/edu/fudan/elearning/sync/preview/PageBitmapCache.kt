package edu.fudan.elearning.sync.preview

import android.graphics.Bitmap
import android.util.LruCache
import java.util.Collections

/**
 * 页面位图缓存：按字节数预算的 LRU。展示中的页会被 [pin] 住，淘汰时不回收， 避免渲染到已 recycle 的位图导致崩溃；离开可视区后 [unpin]，可被正常回收。
 *
 * 预算按设备堆大小自适应（堆的 1/8，夹在 32–96 MiB），既能在低内存设备上留出 解码余量，也能让大屏设备少重复渲染。
 */
class PageBitmapCache(maxBytes: Int = defaultMaxBytes()) {
    private val pinned: MutableSet<Int> =
        Collections.synchronizedSet(Collections.newSetFromMap(HashMap()))

    private val lru =
        object : LruCache<Int, Bitmap>(maxBytes) {
            override fun sizeOf(key: Int, value: Bitmap): Int =
                runCatching { value.byteCount }.getOrDefault(1)

            override fun entryRemoved(
                evicted: Boolean,
                key: Int,
                oldValue: Bitmap,
                newValue: Bitmap?,
            ) {
                if (evicted && !pinned.contains(key) && !oldValue.isRecycled) {
                    runCatching { oldValue.recycle() }
                }
            }
        }

    operator fun get(key: Int): Bitmap? = lru.get(key)

    fun put(key: Int, value: Bitmap) {
        lru.put(key, value)
    }

    fun pin(key: Int) {
        pinned.add(key)
    }

    fun unpin(key: Int) {
        pinned.remove(key)
    }

    companion object {
        private const val MIN_MAX_BYTES = 32L * 1024 * 1024
        private const val MAX_MAX_BYTES = 96L * 1024 * 1024

        /** 位图缓存预算：堆的 1/8，最低 32 MiB、最高 96 MiB。 */
        fun defaultMaxBytes(maxHeapBytes: Long = Runtime.getRuntime().maxMemory()): Int =
            (maxHeapBytes / 8).coerceIn(MIN_MAX_BYTES, MAX_MAX_BYTES).toInt()
    }
}
