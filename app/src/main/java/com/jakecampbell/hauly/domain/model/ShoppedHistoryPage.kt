package com.jakecampbell.hauly.domain.model

/**
 * One page of the shopped-items browse (most recently edited first). The set
 * grows without bound, so it is fetched page by page; [nextCursor] is non-null
 * while more pages exist.
 */
data class ShoppedHistoryPage(
    val items: List<ShoppingItem>,
    val nextCursor: String?,
)
