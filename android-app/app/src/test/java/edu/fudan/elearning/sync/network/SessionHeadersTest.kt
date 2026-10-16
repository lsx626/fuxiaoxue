package edu.fudan.elearning.sync.network

import org.junit.Assert.*
import org.junit.Test

class SessionHeadersTest {
    @Test fun sessionOnlyGoesToCanvasHttpsOrigin() {
        val session = SessionHeaders("synthetic", "test", "_canvas_session")
        assertEquals("_canvas_session=synthetic", session.cookieFor("elearning.fudan.edu.cn", true))
        for (host in listOf("files.example.org", "elearning.fudan.edu.cn.evil.test", "id.fudan.edu.cn"))
            assertNull(session.cookieFor(host, true))
        assertNull(session.cookieFor("elearning.fudan.edu.cn", false))
        assertNull(SessionHeaders().cookieFor("elearning.fudan.edu.cn", true))
    }
}
