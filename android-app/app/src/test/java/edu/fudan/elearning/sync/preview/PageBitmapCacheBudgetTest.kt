package edu.fudan.elearning.sync.preview

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 页位图缓存预算的 JVM 单测。
 *
 * 预算按设备堆大小自适应：低内存设备必须至少留出 32 MiB 给渲染，超大堆设备 也不能把预算抬到失控（上限 96 MiB）。
 */
class PageBitmapCacheBudgetTest {

    private val mib = 1024L * 1024L

    @Test
    fun budget_followsHeapWithFloorAndCeiling() {
        // 小堆：8 MiB 的理论值被抬到 32 MiB 下限
        assertEquals(32 * 1024 * 1024, PageBitmapCache.defaultMaxBytes(64 * mib))
        // 常见手机堆：384 MiB / 8 = 48 MiB
        assertEquals(48 * 1024 * 1024, PageBitmapCache.defaultMaxBytes(384 * mib))
        // 超大堆：封顶 96 MiB
        assertEquals(96 * 1024 * 1024, PageBitmapCache.defaultMaxBytes(8L * 1024 * mib))
    }

    @Test
    fun budget_neverZeroForTinyHeap() {
        assertEquals(32 * 1024 * 1024, PageBitmapCache.defaultMaxBytes(1))
    }
}
