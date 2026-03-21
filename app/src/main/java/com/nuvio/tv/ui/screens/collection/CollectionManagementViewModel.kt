package com.nuvio.tv.ui.screens.collection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.local.CollectionsDataStore
import com.nuvio.tv.domain.model.Collection
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CollectionManagementUiState(
    val collections: List<Collection> = emptyList(),
    val isLoading: Boolean = true
)

@HiltViewModel
class CollectionManagementViewModel @Inject constructor(
    private val collectionsDataStore: CollectionsDataStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(CollectionManagementUiState())
    val uiState: StateFlow<CollectionManagementUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            collectionsDataStore.collections.collectLatest { collections ->
                _uiState.update {
                    it.copy(collections = collections, isLoading = false)
                }
            }
        }
    }

    fun deleteCollection(collectionId: String) {
        viewModelScope.launch {
            collectionsDataStore.removeCollection(collectionId)
        }
    }

    fun moveUp(index: Int) {
        if (index <= 0) return
        viewModelScope.launch {
            val current = _uiState.value.collections.toMutableList()
            val item = current.removeAt(index)
            current.add(index - 1, item)
            collectionsDataStore.setCollections(current)
        }
    }

    fun moveDown(index: Int) {
        val current = _uiState.value.collections
        if (index >= current.size - 1) return
        viewModelScope.launch {
            val mutableList = current.toMutableList()
            val item = mutableList.removeAt(index)
            mutableList.add(index + 1, item)
            collectionsDataStore.setCollections(mutableList)
        }
    }
}
