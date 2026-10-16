package edu.fudan.elearning.sync.worker

import edu.fudan.elearning.sync.sync.SyncResult
import org.junit.Assert.assertEquals
import org.junit.Test

class BackgroundPolicyTest {
    @Test fun authenticationFailureRequiresUserAction() {
        assertEquals(BackgroundDecision.FAIL, backgroundDecision(SyncResult(error = "登录失效", needsReauth = true), 0))
    }
    @Test fun temporaryFailuresRetryWithBoundedAttempts() {
        val result = SyncResult(error = "课程未完成", failedCourses = 1, retryable = true)
        assertEquals(BackgroundDecision.RETRY, backgroundDecision(result, 0))
        assertEquals(BackgroundDecision.FAIL, backgroundDecision(result, 3))
    }
    @Test fun successAndPermanentFailureRemainDistinct() {
        assertEquals(BackgroundDecision.SUCCESS, backgroundDecision(SyncResult(), 0))
        assertEquals(BackgroundDecision.FAIL, backgroundDecision(SyncResult(error = "数据格式异常"), 0))
    }
}
