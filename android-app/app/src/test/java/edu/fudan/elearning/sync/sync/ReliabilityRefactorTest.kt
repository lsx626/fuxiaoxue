package edu.fudan.elearning.sync.sync

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class ReliabilityRefactorTest {
    @Test fun completedDownloadsAreConsumedBeforeSlowDownloadFinishes() = runBlocking {
        val releaseSlow = CompletableDeferred<Unit>()
        val received = mutableListOf<Int>()
        var active = 0
        var peak = 0
        processDownloads(listOf(1, 2, 3, 4), 2, download = { task ->
            active++; peak = maxOf(peak, active)
            if (task == 1) releaseSlow.await() else delay(5)
            active--
            task
        }) { _, result ->
            received += result
            if (result == 2) releaseSlow.complete(Unit)
        }
        assertEquals(2, received.first())
        assertTrue(peak <= 2)
        assertEquals(setOf(1, 2, 3, 4), received.toSet())
    }

    @Test fun consumerFailureCancelsRemainingDownloads() = runBlocking {
        var cancelled = false
        try {
            processDownloads(listOf(1, 2), 2, download = { id ->
                if (id == 2) try { awaitCancellation() } finally { cancelled = true }
                else { yield(); id }
            }) { _, _ -> throw IllegalStateException("authentication failed") }
            fail("failure must propagate")
        } catch (_: IllegalStateException) { }
        assertTrue(cancelled)
    }

    @Test fun atomicReplacementKeepsGoodCopyUntilCommit() {
        val dir = Files.createTempDirectory("fxx-commit").toFile()
        try {
            val dest = java.io.File(dir, "lecture.pdf").apply { writeText("old") }
            val part = DownloadPlan.partFile(dest).apply { writeText("new") }
            assertEquals("old", dest.readText())
            DownloadPlan.commitPart(part, dest)
            assertEquals("new", dest.readText())
            assertFalse(part.exists())
            val blocked = java.io.File(dir, "blocked").apply { mkdir() }
            java.io.File(blocked, "keep").writeText("keep")
            part.writeText("retry")
            try { DownloadPlan.commitPart(part, blocked); fail("must fail") } catch (_: java.io.IOException) { }
            assertEquals("retry", part.readText())
            assertEquals("keep", java.io.File(blocked, "keep").readText())
        } finally { dir.deleteRecursively() }
    }

    @Test fun rangeMustMatchRequestedOffset() {
        assertTrue(DownloadPlan.rangeStartsAt("bytes 120-199/200", 120))
        assertFalse(DownloadPlan.rangeStartsAt("bytes 0-199/200", 120))
        assertFalse(DownloadPlan.rangeStartsAt(null, 120))
    }
}
