package dev.butterflysky.argus.common

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ApplicationsPaginatorTest {
    @Test
    fun `paginates and clamps page index`() {
        val items = (1..12).toList()
        val first = ApplicationsPaginator.paginate(items, page = 0, pageSize = 5)
        assertEquals(0, first.page)
        assertEquals(3, first.totalPages)
        assertEquals(listOf(1, 2, 3, 4, 5), first.items)

        val mid = ApplicationsPaginator.paginate(items, page = 1, pageSize = 5)
        assertEquals(listOf(6, 7, 8, 9, 10), mid.items)

        val clamped = ApplicationsPaginator.paginate(items, page = 10, pageSize = 5)
        assertEquals(2, clamped.page)
        assertEquals(listOf(11, 12), clamped.items)
    }
}
