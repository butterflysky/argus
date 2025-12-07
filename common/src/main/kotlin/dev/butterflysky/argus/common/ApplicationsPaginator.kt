package dev.butterflysky.argus.common

data class ApplicationsPage<T>(
    val page: Int,
    val totalPages: Int,
    val items: List<T>,
)

/** Simple utility to page application lists for Discord embeds/buttons. */
object ApplicationsPaginator {
    fun <T> paginate(
        items: List<T>,
        page: Int,
        pageSize: Int = 5,
    ): ApplicationsPage<T> {
        if (items.isEmpty()) return ApplicationsPage(0, 0, emptyList())
        val safePage = page.coerceIn(0, (items.size - 1) / pageSize)
        val totalPages = (items.size + pageSize - 1) / pageSize
        val slice = items.drop(safePage * pageSize).take(pageSize)
        return ApplicationsPage(safePage, totalPages, slice)
    }
}
