package com.nuvio.tv.ui.screens.collection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Tab
import androidx.tv.material3.TabRow
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.nuvio.tv.domain.model.FolderViewMode
import com.nuvio.tv.domain.model.HomeLayout
import com.nuvio.tv.ui.components.CatalogRowSection
import com.nuvio.tv.ui.components.ContentCard
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.PosterCardDefaults
import com.nuvio.tv.ui.theme.NuvioColors

@OptIn(ExperimentalTvMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun FolderDetailScreen(
    viewModel: FolderDetailViewModel = hiltViewModel(),
    onNavigateToDetail: (String, String, String) -> Unit,
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val folder = uiState.folder

    if (uiState.isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            LoadingIndicator()
        }
        return
    }

    if (folder == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Folder not found", color = NuvioColors.TextSecondary)
        }
        return
    }

    val effectiveViewMode = when (uiState.viewMode) {
        FolderViewMode.FOLLOW_LAYOUT -> when (uiState.homeLayout) {
            HomeLayout.CLASSIC -> FolderViewMode.ROWS
            HomeLayout.GRID -> FolderViewMode.TABBED_GRID
            HomeLayout.MODERN -> FolderViewMode.ROWS
        }
        else -> uiState.viewMode
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 24.dp)
    ) {
        FolderHeader(folder = folder)

        when (effectiveViewMode) {
            FolderViewMode.TABBED_GRID -> TabbedGridContent(
                uiState = uiState,
                onSelectTab = viewModel::selectTab,
                onNavigateToDetail = onNavigateToDetail
            )
            FolderViewMode.ROWS -> RowsContent(
                uiState = uiState,
                onNavigateToDetail = onNavigateToDetail
            )
            FolderViewMode.FOLLOW_LAYOUT -> {} // resolved above, won't reach here
        }
    }
}

@Composable
private fun FolderHeader(folder: com.nuvio.tv.domain.model.CollectionFolder) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 48.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (!folder.coverImageUrl.isNullOrBlank()) {
            AsyncImage(
                model = folder.coverImageUrl,
                contentDescription = folder.title,
                modifier = Modifier
                    .width(48.dp)
                    .height(48.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop
            )
        }
        Text(
            text = folder.title,
            style = MaterialTheme.typography.headlineMedium,
            color = NuvioColors.TextPrimary
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun TabbedGridContent(
    uiState: FolderDetailUiState,
    onSelectTab: (Int) -> Unit,
    onNavigateToDetail: (String, String, String) -> Unit
) {
    val tabFocusRequesters = remember(uiState.tabs.size) { uiState.tabs.indices.map { FocusRequester() } }

    if (uiState.tabs.size > 1) {
        TabRow(
            selectedTabIndex = uiState.selectedTabIndex,
            modifier = Modifier
                .padding(horizontal = 48.dp, vertical = 4.dp)
                .focusRestorer {
                    tabFocusRequesters.getOrNull(uiState.selectedTabIndex) ?: FocusRequester.Default
                }
        ) {
            uiState.tabs.forEachIndexed { index, tab ->
                Tab(
                    selected = index == uiState.selectedTabIndex,
                    onFocus = { onSelectTab(index) },
                    onClick = { onSelectTab(index) },
                    modifier = if (index < tabFocusRequesters.size) {
                        Modifier.focusRequester(tabFocusRequesters[index])
                    } else Modifier
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.Start
                    ) {
                        Text(
                            text = tab.label,
                            style = MaterialTheme.typography.labelLarge
                        )
                        Text(
                            text = tab.typeLabel.ifBlank { " " },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (tab.typeLabel.isNotBlank()) NuvioColors.TextTertiary else Color.Transparent
                        )
                    }
                }
            }
        }
    }

    val currentTab = uiState.tabs.getOrNull(uiState.selectedTabIndex)
    if (currentTab == null) return

    when {
        currentTab.isLoading -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                LoadingIndicator()
            }
        }
        currentTab.error != null -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = currentTab.error,
                    color = NuvioColors.TextSecondary
                )
            }
        }
        currentTab.catalogRow != null -> {
            val items = currentTab.catalogRow.items
            val posterCardStyle = PosterCardDefaults.Style
            val itemFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }
            var lastFocusedItemKey by remember { mutableStateOf<String?>(null) }

            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = posterCardStyle.width),
                modifier = Modifier
                    .fillMaxSize()
                    .focusRestorer {
                        lastFocusedItemKey?.let { itemFocusRequesters[it] } ?: FocusRequester.Default
                    },
                contentPadding = PaddingValues(
                    start = 48.dp,
                    end = 48.dp,
                    top = 16.dp,
                    bottom = 48.dp
                ),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                itemsIndexed(
                    items = items,
                    key = { index, item -> "${item.id}_$index" }
                ) { index, item ->
                    val itemKey = "${item.id}_$index"
                    val focusReq = itemFocusRequesters.getOrPut(itemKey) { FocusRequester() }
                    ContentCard(
                        item = item,
                        posterCardStyle = posterCardStyle,
                        focusRequester = focusReq,
                        onFocus = { _ -> lastFocusedItemKey = itemKey },
                        onClick = {
                            onNavigateToDetail(
                                item.id,
                                item.apiType,
                                currentTab.catalogRow.addonBaseUrl
                            )
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun RowsContent(
    uiState: FolderDetailUiState,
    onNavigateToDetail: (String, String, String) -> Unit
) {
    val sourceTabs = uiState.tabs.filter { !it.isAllTab }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 16.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        sourceTabs.forEachIndexed { index, tab ->
            item(key = "row_${index}_${tab.label}") {
                when {
                    tab.isLoading -> {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = tab.label,
                                style = MaterialTheme.typography.headlineSmall,
                                color = NuvioColors.TextPrimary,
                                modifier = Modifier.padding(start = 48.dp, end = 48.dp, bottom = 12.dp)
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(PosterCardDefaults.Style.height),
                                contentAlignment = Alignment.Center
                            ) {
                                LoadingIndicator()
                            }
                        }
                    }
                    tab.error != null -> {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = tab.label,
                                style = MaterialTheme.typography.headlineSmall,
                                color = NuvioColors.TextPrimary,
                                modifier = Modifier.padding(start = 48.dp, end = 48.dp, bottom = 12.dp)
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(PosterCardDefaults.Style.height),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(text = tab.error, color = NuvioColors.TextSecondary)
                            }
                        }
                    }
                    tab.catalogRow != null -> {
                        CatalogRowSection(
                            catalogRow = tab.catalogRow,
                            onItemClick = onNavigateToDetail,
                            showPosterLabels = true,
                            showAddonName = false,
                            showCatalogTypeSuffix = false
                        )
                    }
                }
            }
        }
    }
}
