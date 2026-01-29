package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.domain.model.CatalogRow

data class HomeUiState(
    val catalogRows: List<CatalogRow> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val selectedItemId: String? = null
)

sealed class HomeEvent {
    data class OnItemClick(val itemId: String, val itemType: String) : HomeEvent()
    data class OnLoadMoreCatalog(val catalogId: String, val addonId: String) : HomeEvent()
    data object OnRetry : HomeEvent()
}
