package com.nuvio.tv.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.foundation.lazy.list.TvLazyColumn
import androidx.tv.foundation.lazy.list.items
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.nuvio.tv.ui.components.CatalogRowSection
import com.nuvio.tv.ui.components.ErrorState
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.NuvioTopBar
import com.nuvio.tv.ui.theme.NuvioColors

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel = hiltViewModel(),
    onNavigateToDetail: (String, String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioColors.Background)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            NuvioTopBar()

            when {
                uiState.isLoading && uiState.catalogRows.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        LoadingIndicator()
                    }
                }
                uiState.error != null && uiState.catalogRows.isEmpty() -> {
                    ErrorState(
                        message = uiState.error ?: "An error occurred",
                        onRetry = { viewModel.onEvent(HomeEvent.OnRetry) }
                    )
                }
                else -> {
                    TvLazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(32.dp)
                    ) {
                        items(
                            items = uiState.catalogRows,
                            key = { "${it.addonId}_${it.catalogId}" }
                        ) { catalogRow ->
                            CatalogRowSection(
                                catalogRow = catalogRow,
                                onItemClick = { id, type ->
                                    onNavigateToDetail(id, type)
                                },
                                onLoadMore = {
                                    viewModel.onEvent(
                                        HomeEvent.OnLoadMoreCatalog(
                                            catalogId = catalogRow.catalogId,
                                            addonId = catalogRow.addonId
                                        )
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
