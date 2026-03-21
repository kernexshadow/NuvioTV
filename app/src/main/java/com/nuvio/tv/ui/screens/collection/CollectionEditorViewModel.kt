package com.nuvio.tv.ui.screens.collection

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.local.CollectionsDataStore
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.Collection
import com.nuvio.tv.domain.model.CollectionCatalogSource
import com.nuvio.tv.domain.model.CollectionFolder
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.repository.AddonRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CollectionEditorUiState(
    val isNew: Boolean = true,
    val collectionId: String = "",
    val title: String = "",
    val folders: List<CollectionFolder> = emptyList(),
    val isLoading: Boolean = true,
    val availableCatalogs: List<AvailableCatalog> = emptyList(),
    val editingFolder: CollectionFolder? = null,
    val showFolderEditor: Boolean = false,
    val showCatalogPicker: Boolean = false
)

data class AvailableCatalog(
    val addonId: String,
    val addonName: String,
    val type: String,
    val catalogId: String,
    val catalogName: String
)

@HiltViewModel
class CollectionEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val collectionsDataStore: CollectionsDataStore,
    private val addonRepository: AddonRepository
) : ViewModel() {

    private val collectionIdArg: String = savedStateHandle["collectionId"] ?: ""

    private val _uiState = MutableStateFlow(CollectionEditorUiState())
    val uiState: StateFlow<CollectionEditorUiState> = _uiState.asStateFlow()

    init {
        loadData()
    }

    private fun loadData() {
        viewModelScope.launch {
            val addons = addonRepository.getInstalledAddons().first()
            val availableCatalogs = addons.flatMap { addon ->
                addon.catalogs.map { catalog ->
                    AvailableCatalog(
                        addonId = addon.id,
                        addonName = addon.displayName,
                        type = catalog.apiType,
                        catalogId = catalog.id,
                        catalogName = catalog.name
                    )
                }
            }

            if (collectionIdArg.isNotBlank()) {
                val collections = collectionsDataStore.collections.first()
                val existing = collections.find { it.id == collectionIdArg }
                if (existing != null) {
                    _uiState.update {
                        it.copy(
                            isNew = false,
                            collectionId = existing.id,
                            title = existing.title,
                            folders = existing.folders,
                            availableCatalogs = availableCatalogs,
                            isLoading = false
                        )
                    }
                    return@launch
                }
            }

            _uiState.update {
                it.copy(
                    isNew = true,
                    collectionId = collectionsDataStore.generateId(),
                    availableCatalogs = availableCatalogs,
                    isLoading = false
                )
            }
        }
    }

    fun setTitle(title: String) {
        _uiState.update { it.copy(title = title) }
    }

    fun addFolder() {
        val newFolder = CollectionFolder(
            id = collectionsDataStore.generateId(),
            title = "",
            tileShape = PosterShape.POSTER
        )
        _uiState.update {
            it.copy(editingFolder = newFolder, showFolderEditor = true)
        }
    }

    fun editFolder(folderId: String) {
        val folder = _uiState.value.folders.find { it.id == folderId } ?: return
        _uiState.update { it.copy(editingFolder = folder, showFolderEditor = true) }
    }

    fun removeFolder(folderId: String) {
        _uiState.update { state ->
            state.copy(folders = state.folders.filter { it.id != folderId })
        }
    }

    fun moveFolderUp(index: Int) {
        if (index <= 0) return
        _uiState.update { state ->
            val folders = state.folders.toMutableList()
            val item = folders.removeAt(index)
            folders.add(index - 1, item)
            state.copy(folders = folders)
        }
    }

    fun moveFolderDown(index: Int) {
        val folders = _uiState.value.folders
        if (index >= folders.size - 1) return
        _uiState.update { state ->
            val mutableFolders = state.folders.toMutableList()
            val item = mutableFolders.removeAt(index)
            mutableFolders.add(index + 1, item)
            state.copy(folders = mutableFolders)
        }
    }

    fun updateFolderTitle(title: String) {
        _uiState.update { state ->
            state.copy(editingFolder = state.editingFolder?.copy(title = title))
        }
    }

    fun updateFolderCoverImage(url: String) {
        _uiState.update { state ->
            state.copy(editingFolder = state.editingFolder?.copy(coverImageUrl = url.ifBlank { null }))
        }
    }

    fun updateFolderTileShape(shape: PosterShape) {
        _uiState.update { state ->
            state.copy(editingFolder = state.editingFolder?.copy(tileShape = shape))
        }
    }

    fun updateFolderHideTitle(hide: Boolean) {
        _uiState.update { state ->
            state.copy(editingFolder = state.editingFolder?.copy(hideTitle = hide))
        }
    }

    fun addCatalogSource(catalog: AvailableCatalog) {
        _uiState.update { state ->
            val folder = state.editingFolder ?: return@update state
            val source = CollectionCatalogSource(
                addonId = catalog.addonId,
                type = catalog.type,
                catalogId = catalog.catalogId
            )
            if (folder.catalogSources.any { it.addonId == source.addonId && it.type == source.type && it.catalogId == source.catalogId }) {
                return@update state
            }
            state.copy(
                editingFolder = folder.copy(catalogSources = folder.catalogSources + source),
                showCatalogPicker = false
            )
        }
    }

    fun removeCatalogSource(index: Int) {
        _uiState.update { state ->
            val folder = state.editingFolder ?: return@update state
            val sources = folder.catalogSources.toMutableList()
            if (index in sources.indices) sources.removeAt(index)
            state.copy(editingFolder = folder.copy(catalogSources = sources))
        }
    }

    fun toggleCatalogSource(catalog: AvailableCatalog) {
        _uiState.update { state ->
            val folder = state.editingFolder ?: return@update state
            val existing = folder.catalogSources.indexOfFirst {
                it.addonId == catalog.addonId && it.type == catalog.type && it.catalogId == catalog.catalogId
            }
            val newSources = if (existing >= 0) {
                folder.catalogSources.toMutableList().also { it.removeAt(existing) }
            } else {
                folder.catalogSources + CollectionCatalogSource(
                    addonId = catalog.addonId,
                    type = catalog.type,
                    catalogId = catalog.catalogId
                )
            }
            state.copy(editingFolder = folder.copy(catalogSources = newSources))
        }
    }

    fun showCatalogPicker() {
        _uiState.update { it.copy(showCatalogPicker = true) }
    }

    fun hideCatalogPicker() {
        _uiState.update { it.copy(showCatalogPicker = false) }
    }

    fun saveFolderEdit() {
        val rawFolder = _uiState.value.editingFolder ?: return
        if (rawFolder.catalogSources.isEmpty()) return
        val editingFolder = if (rawFolder.title.isBlank()) rawFolder.copy(title = "Untitled") else rawFolder
        _uiState.update { state ->
            val existingIndex = state.folders.indexOfFirst { it.id == editingFolder.id }
            val newFolders = if (existingIndex >= 0) {
                state.folders.toMutableList().also { it[existingIndex] = editingFolder }
            } else {
                state.folders + editingFolder
            }
            state.copy(folders = newFolders, showFolderEditor = false, editingFolder = null)
        }
    }

    fun cancelFolderEdit() {
        _uiState.update { it.copy(showFolderEditor = false, editingFolder = null) }
    }

    fun save(onComplete: () -> Unit) {
        val state = _uiState.value
        if (state.folders.isEmpty()) return
        viewModelScope.launch {
            val collection = Collection(
                id = state.collectionId,
                title = state.title.ifBlank { "Untitled Collection" },
                folders = state.folders
            )

            if (state.isNew) {
                collectionsDataStore.addCollection(collection)
            } else {
                collectionsDataStore.updateCollection(collection)
            }
            onComplete()
        }
    }
}
