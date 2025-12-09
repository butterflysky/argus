package dev.butterflysky.argus.common

import kotlin.test.Test
import kotlin.test.assertEquals

class ApplicationsPaginatorTest {
    @Test
    fun `paginates with bounds and page count`() {
        val items = (1..12).toList()

        val first = ApplicationsPaginator.paginate(items, page = 0, pageSize = 5)
        assertEquals(0, first.page)
        assertEquals(3, first.totalPages)
        assertEquals(listOf(1, 2, 3, 4, 5), first.items)

        val middle = ApplicationsPaginator.paginate(items, page = 1, pageSize = 5)
        assertEquals(listOf(6, 7, 8, 9, 10), middle.items)

        // Requesting out-of-range page clamps to last.
        val last = ApplicationsPaginator.paginate(items, page = 99, pageSize = 5)
        assertEquals(2, last.page)
        assertEquals(listOf(11, 12), last.items)
    }
}
