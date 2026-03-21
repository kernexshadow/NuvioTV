package com.nuvio.tv.ui.screens.collection

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.data.local.CollectionsDataStore
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.CollectionCatalogSource
import com.nuvio.tv.domain.model.CollectionFolder
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.CatalogRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FolderDetailUiState(
    val folder: CollectionFolder? = null,
    val collectionTitle: String = "",
    val tabs: List<FolderTab> = emptyList(),
    val selectedTabIndex: Int = 0,
    val isLoading: Boolean = true
)

data class FolderTab(
    val label: String,
    val typeLabel: String = "",
    val catalogRow: CatalogRow? = null,
    val isLoading: Boolean = true,
    val error: String? = null
)

@HiltViewModel
class FolderDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val collectionsDataStore: CollectionsDataStore,
    private val addonRepository: AddonRepository,
    private val catalogRepository: CatalogRepository
) : ViewModel() {

    private val collectionId: String = savedStateHandle["collectionId"] ?: ""
    private val folderId: String = savedStateHandle["folderId"] ?: ""

    private val _uiState = MutableStateFlow(FolderDetailUiState())
    val uiState: StateFlow<FolderDetailUiState> = _uiState.asStateFlow()

    init {
        loadFolder()
    }

    private fun loadFolder() {
        viewModelScope.launch {
            val collections = collectionsDataStore.collections.first()
            val collection = collections.find { it.id == collectionId }
            val folder = collection?.folders?.find { it.id == folderId }

            if (folder == null || folder.catalogSources.isEmpty()) {
                _uiState.update {
                    it.copy(
                        folder = folder,
                        collectionTitle = collection?.title ?: "",
                        isLoading = false
                    )
                }
                return@launch
            }

            val addons = addonRepository.getInstalledAddons().first()

            val tabs = folder.catalogSources.map { source ->
                val addon = addons.find { it.id == source.addonId }
                val catalog = addon?.catalogs?.find { it.id == source.catalogId && it.apiType == source.type }
                val (name, typeLabel) = buildTabLabels(source, catalog?.name)
                FolderTab(label = name, typeLabel = typeLabel, isLoading = true)
            }

            _uiState.update {
                it.copy(
                    folder = folder,
                    collectionTitle = collection?.title ?: "",
                    tabs = tabs,
                    isLoading = false
                )
            }

            folder.catalogSources.forEachIndexed { index, source ->
                loadCatalogForTab(index, source)
            }
        }
    }

    private fun loadCatalogForTab(tabIndex: Int, source: CollectionCatalogSource) {
        viewModelScope.launch {
            val addons = addonRepository.getInstalledAddons().first()
            val addon = addons.find { it.id == source.addonId }

            if (addon == null) {
                _uiState.update { state ->
                    val tabs = state.tabs.toMutableList()
                    if (tabIndex < tabs.size) {
                        tabs[tabIndex] = tabs[tabIndex].copy(isLoading = false, error = "Addon not found")
                    }
                    state.copy(tabs = tabs)
                }
                return@launch
            }

            catalogRepository.getCatalog(
                addonBaseUrl = addon.baseUrl,
                addonId = addon.id,
                addonName = addon.displayName,
                catalogId = source.catalogId,
                catalogName = source.catalogId,
                type = source.type,
                skip = 0
            ).collect { result ->
                when (result) {
                    is NetworkResult.Success -> {
                        _uiState.update { state ->
                            val tabs = state.tabs.toMutableList()
                            if (tabIndex < tabs.size) {
                                tabs[tabIndex] = tabs[tabIndex].copy(
                                    catalogRow = result.data,
                                    isLoading = false
                                )
                            }
                            state.copy(tabs = tabs)
                        }
                    }
                    is NetworkResult.Error -> {
                        _uiState.update { state ->
                            val tabs = state.tabs.toMutableList()
                            if (tabIndex < tabs.size) {
                                tabs[tabIndex] = tabs[tabIndex].copy(isLoading = false, error = result.message)
                            }
                            state.copy(tabs = tabs)
                        }
                    }
                    NetworkResult.Loading -> {}
                }
            }
        }
    }

    fun selectTab(index: Int) {
        _uiState.update { it.copy(selectedTabIndex = index) }
    }

    private fun buildTabLabels(source: CollectionCatalogSource, catalogName: String?): Pair<String, String> {
        val typeLabel = when (source.type.lowercase()) {
            "movie" -> "Movies"
            "series" -> "Series"
            else -> source.type.replaceFirstChar { it.uppercase() }
        }
        val name = if (!catalogName.isNullOrBlank()) {
            catalogName.replaceFirstChar { it.uppercase() }
        } else {
            typeLabel
        }
        return name to typeLabel
    }
}
