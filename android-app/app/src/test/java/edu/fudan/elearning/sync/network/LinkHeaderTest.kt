package edu.fudan.elearning.sync.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Canvas 分页 Link 头解析的单测。 */
class LinkHeaderTest {

    @Test
    fun next_picksRelNextFromMultipleLinks() {
        val header =
            "<https://h/api/v1/courses?page=2&per_page=100>; rel=\"next\", " +
                "<https://h/api/v1/courses?page=9&per_page=100>; rel=\"last\""
        assertEquals("https://h/api/v1/courses?page=2&per_page=100", LinkHeader.next(header))
    }

    @Test
    fun next_returnsNullWhenOnlyLastOrPrev() {
        assertNull(LinkHeader.next("<https://h/api/v1/courses?page=9>; rel=\"last\""))
        assertNull(LinkHeader.next("<https://h/api/v1/courses?page=1>; rel=\"prev\""))
        assertNull(LinkHeader.next(null))
        assertNull(LinkHeader.next(""))
    }

    @Test
    fun next_keepsUrlOpaqueIncludingCommasAndBookmarks() {
        // URL 里带逗号与 bookmark：必须原样返回，不能按逗号切断
        val header = "<https://h/api/v1/courses?page=2&include[]=a,b&bookmark=xyz>; rel=\"next\""
        assertEquals(
            "https://h/api/v1/courses?page=2&include[]=a,b&bookmark=xyz",
            LinkHeader.next(header),
        )
    }

    @Test
    fun next_supportsUnquotedRelAndExtraParams() {
        val header = "<https://h/p?page=3>; type=\"application/json\"; rel=next"
        assertEquals("https://h/p?page=3", LinkHeader.next(header))
    }

    @Test
    fun next_ignoresCommaInsideQuotedParams() {
        val header = "<https://h/p?page=2>; title=\"a,b\"; rel=\"next\""
        assertEquals("https://h/p?page=2", LinkHeader.next(header))
    }
}
