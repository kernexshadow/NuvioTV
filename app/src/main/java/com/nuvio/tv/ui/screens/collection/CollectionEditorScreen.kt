package com.nuvio.tv.ui.screens.collection

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Switch
import androidx.tv.material3.SwitchDefaults
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.domain.model.AddonCatalogCollectionSource
import com.nuvio.tv.domain.model.CollectionFolder
import com.nuvio.tv.domain.model.CollectionSource
import com.nuvio.tv.domain.model.FolderViewMode
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.TmdbCollectionFilters
import com.nuvio.tv.domain.model.TmdbCollectionMediaType
import com.nuvio.tv.domain.model.TmdbCollectionSort
import com.nuvio.tv.domain.model.TmdbCollectionSource
import com.nuvio.tv.domain.model.TmdbCollectionSourceType
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.theme.NuvioColors
import com.nuvio.tv.R
import androidx.compose.ui.res.stringResource

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun NuvioTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    focusRequester: FocusRequester? = null
) {
    var isEditing by remember { mutableStateOf(false) }
    val textFieldFocusRequester = remember { FocusRequester() }
    val surfaceFocusRequester = focusRequester ?: remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(isEditing) {
        if (isEditing) {
            repeat(3) { androidx.compose.runtime.withFrameNanos { } }
            try { textFieldFocusRequester.requestFocus() } catch (_: Exception) {}
        }
    }

    Surface(
        onClick = { isEditing = true },
        modifier = modifier.focusRequester(surfaceFocusRequester),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = NuvioColors.BackgroundElevated,
            focusedContainerColor = NuvioColors.BackgroundElevated
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                border = BorderStroke(1.dp, NuvioColors.Border),
                shape = RoundedCornerShape(12.dp)
            ),
            focusedBorder = Border(
                border = BorderStroke(2.dp, NuvioColors.FocusRing),
                shape = RoundedCornerShape(12.dp)
            )
        ),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
    ) {
        Box(modifier = Modifier.padding(12.dp)) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(textFieldFocusRequester)
                    .onFocusChanged {
                        if (!it.isFocused && isEditing) {
                            isEditing = false
                            keyboardController?.hide()
                        }
                    },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = {
                        isEditing = false
                        keyboardController?.hide()
                        surfaceFocusRequester.requestFocus()
                    }
                ),
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = NuvioColors.TextPrimary
                ),
                cursorBrush = SolidColor(if (isEditing) NuvioColors.Primary else Color.Transparent),
                decorationBox = { innerTextField ->
                    if (value.isEmpty() && placeholder.isNotEmpty()) {
                        Text(
                            text = placeholder,
                            style = MaterialTheme.typography.bodyMedium,
                            color = NuvioColors.TextTertiary
                        )
                    }
                    innerTextField()
                }
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun NuvioButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier.then(if (!enabled) Modifier.alpha(0.35f) else Modifier),
        enabled = enabled,
        colors = ButtonDefaults.colors(
            containerColor = NuvioColors.BackgroundCard,
            contentColor = NuvioColors.TextPrimary,
            focusedContainerColor = NuvioColors.FocusBackground,
            focusedContentColor = NuvioColors.Primary
        ),
        border = ButtonDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, NuvioColors.FocusRing),
                shape = RoundedCornerShape(12.dp)
            )
        ),
        shape = ButtonDefaults.shape(RoundedCornerShape(12.dp)),
        content = { content() }
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun CollectionEditorScreen(
    viewModel: CollectionEditorViewModel = hiltViewModel(),
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    if (uiState.isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            LoadingIndicator()
        }
        return
    }

    if (uiState.showFolderEditor) {
        FolderEditorContent(
            viewModel = viewModel,
            uiState = uiState
        )
        return
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 48.dp),
        contentPadding = PaddingValues(start = 48.dp, end = 48.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        item(key = "header") {
            Text(
                text = if (uiState.isNew) stringResource(R.string.collections_new) else stringResource(R.string.collections_editor_edit_collection),
                style = MaterialTheme.typography.headlineMedium,
                color = NuvioColors.TextPrimary
            )
            Spacer(modifier = Modifier.height(24.dp))
        }

        item(key = "title") {
            Text(
                text = stringResource(R.string.collections_editor_row_title),
                style = MaterialTheme.typography.labelLarge,
                color = NuvioColors.TextSecondary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                NuvioTextField(
                    value = uiState.title,
                    onValueChange = { viewModel.setTitle(it) },
                    modifier = Modifier.weight(1f),
                    placeholder = stringResource(R.string.collections_editor_placeholder_name)
                )
                val canSaveCollection = uiState.title.isNotBlank() && uiState.folders.isNotEmpty()
                NuvioButton(onClick = { viewModel.save { onBack() } }, enabled = canSaveCollection) {
                    Text(stringResource(R.string.collections_editor_save))
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        item(key = "backdrop") {
            Text(
                text = stringResource(R.string.collections_editor_backdrop),
                style = MaterialTheme.typography.labelLarge,
                color = NuvioColors.TextSecondary
            )
            Spacer(modifier = Modifier.height(8.dp))
            NuvioTextField(
                value = uiState.backdropImageUrl,
                onValueChange = { viewModel.setBackdropImageUrl(it) },
                modifier = Modifier.fillMaxWidth(),
                placeholder = stringResource(R.string.collections_editor_placeholder_backdrop)
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        item(key = "pin_to_top") {
            Card(
                onClick = { viewModel.setPinToTop(!uiState.pinToTop) },
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.colors(
                    containerColor = NuvioColors.BackgroundCard,
                    focusedContainerColor = NuvioColors.FocusBackground
                ),
                border = CardDefaults.border(
                    focusedBorder = Border(
                        border = BorderStroke(2.dp, NuvioColors.FocusRing),
                        shape = RoundedCornerShape(12.dp)
                    )
                ),
                shape = CardDefaults.shape(RoundedCornerShape(12.dp)),
                scale = CardDefaults.scale(focusedScale = 1.02f)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.collections_editor_pin_above),
                            style = MaterialTheme.typography.titleMedium,
                            color = NuvioColors.TextPrimary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.collections_editor_pin_above_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = NuvioColors.TextSecondary
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Switch(
                        checked = uiState.pinToTop,
                        onCheckedChange = { viewModel.setPinToTop(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = NuvioColors.Secondary,
                            checkedTrackColor = NuvioColors.Secondary.copy(alpha = 0.3f),
                            uncheckedThumbColor = NuvioColors.TextSecondary,
                            uncheckedTrackColor = NuvioColors.BackgroundCard
                        )
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        item(key = "focus_glow") {
            Card(
                onClick = { viewModel.setFocusGlowEnabled(!uiState.focusGlowEnabled) },
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.colors(
                    containerColor = NuvioColors.BackgroundCard,
                    focusedContainerColor = NuvioColors.FocusBackground
                ),
                border = CardDefaults.border(
                    focusedBorder = Border(
                        border = BorderStroke(2.dp, NuvioColors.FocusRing),
                        shape = RoundedCornerShape(12.dp)
                    )
                ),
                shape = CardDefaults.shape(RoundedCornerShape(12.dp)),
                scale = CardDefaults.scale(focusedScale = 1.02f)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.collections_editor_focus_glow),
                            style = MaterialTheme.typography.titleMedium,
                            color = NuvioColors.TextPrimary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.collections_editor_focus_glow_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = NuvioColors.TextSecondary
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Switch(
                        checked = uiState.focusGlowEnabled,
                        onCheckedChange = { viewModel.setFocusGlowEnabled(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = NuvioColors.Secondary,
                            checkedTrackColor = NuvioColors.Secondary.copy(alpha = 0.3f),
                            uncheckedThumbColor = NuvioColors.TextSecondary,
                            uncheckedTrackColor = NuvioColors.BackgroundCard
                        )
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        item(key = "view_mode") {
            Text(
                text = stringResource(R.string.collections_editor_view_mode),
                style = MaterialTheme.typography.labelLarge,
                color = NuvioColors.TextSecondary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val viewModes = listOf(
                    FolderViewMode.TABBED_GRID to stringResource(R.string.collections_editor_view_mode_tabs),
                    FolderViewMode.ROWS to stringResource(R.string.collections_editor_view_mode_rows),
                    FolderViewMode.FOLLOW_LAYOUT to stringResource(R.string.collections_editor_view_mode_follow)
                )
                viewModes.forEach { (mode, label) ->
                    val isSelected = uiState.viewMode == mode
                    Button(
                        onClick = { viewModel.setViewMode(mode) },
                        colors = ButtonDefaults.colors(
                            containerColor = if (isSelected) NuvioColors.Secondary.copy(alpha = 0.3f) else NuvioColors.BackgroundCard,
                            contentColor = if (isSelected) NuvioColors.Secondary else NuvioColors.TextSecondary,
                            focusedContainerColor = NuvioColors.FocusBackground,
                            focusedContentColor = NuvioColors.Primary
                        ),
                        border = ButtonDefaults.border(
                            border = if (isSelected) Border(
                                border = BorderStroke(2.dp, NuvioColors.Secondary),
                                shape = RoundedCornerShape(12.dp)
                            ) else Border.None,
                            focusedBorder = Border(
                                border = BorderStroke(2.dp, NuvioColors.FocusRing),
                                shape = RoundedCornerShape(12.dp)
                            )
                        ),
                        shape = ButtonDefaults.shape(RoundedCornerShape(12.dp))
                    ) {
                        Text(label)
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        if (uiState.viewMode == FolderViewMode.TABBED_GRID) {
            item(key = "show_all_tab") {
                Card(
                    onClick = { viewModel.setShowAllTab(!uiState.showAllTab) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.colors(
                        containerColor = NuvioColors.BackgroundCard,
                        focusedContainerColor = NuvioColors.FocusBackground
                    ),
                    border = CardDefaults.border(
                        focusedBorder = Border(
                            border = BorderStroke(2.dp, NuvioColors.FocusRing),
                            shape = RoundedCornerShape(12.dp)
                        )
                    ),
                    shape = CardDefaults.shape(RoundedCornerShape(12.dp)),
                    scale = CardDefaults.scale(focusedScale = 1.02f)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.collections_editor_show_all_tab),
                                style = MaterialTheme.typography.titleMedium,
                                color = NuvioColors.TextPrimary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.collections_editor_show_all_tab_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = NuvioColors.TextSecondary
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Switch(
                            checked = uiState.showAllTab,
                            onCheckedChange = { viewModel.setShowAllTab(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = NuvioColors.Secondary,
                                checkedTrackColor = NuvioColors.Secondary.copy(alpha = 0.3f),
                                uncheckedThumbColor = NuvioColors.TextSecondary,
                                uncheckedTrackColor = NuvioColors.BackgroundCard
                            )
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }
        }

        item(key = "folders_header") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.collections_editor_folders),
                    style = MaterialTheme.typography.titleMedium,
                    color = NuvioColors.TextPrimary
                )
                Text(
                    text = "${uiState.folders.size} item${if (uiState.folders.size != 1) "s" else ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioColors.TextTertiary
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        itemsIndexed(
            items = uiState.folders,
            key = { _, folder -> folder.id }
        ) { index, folder ->
            Box(modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp)) {
                FolderListItem(
                    folder = folder,
                    isFirst = index == 0,
                    isLast = index == uiState.folders.size - 1,
                    onEdit = { viewModel.editFolder(folder.id) },
                    onDelete = { viewModel.removeFolder(folder.id) },
                    onMoveUp = { viewModel.moveFolderUp(index) },
                    onMoveDown = { viewModel.moveFolderDown(index) }
                )
            }
        }

        item(key = "add_folder") {
            Box(modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 4.dp)) {
                NuvioButton(onClick = { viewModel.addFolder() }) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = stringResource(R.string.cd_add))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.collections_editor_add_folder))
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun FolderListItem(
    folder: CollectionFolder,
    isFirst: Boolean,
    isLast: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        colors = SurfaceDefaults.colors(containerColor = NuvioColors.BackgroundCard),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = folder.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = NuvioColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${folder.tileShape.name.lowercase().replaceFirstChar { it.uppercase() }} - ${stringResource(R.string.collections_editor_source_count, folder.sources.size)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioColors.TextTertiary
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                NuvioButton(onClick = onMoveUp) {
                    Icon(Icons.Default.KeyboardArrowUp, stringResource(R.string.cd_move_up), tint = if (!isFirst) NuvioColors.TextSecondary else NuvioColors.TextTertiary)
                }
                NuvioButton(onClick = onMoveDown) {
                    Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.cd_move_down), tint = if (!isLast) NuvioColors.TextSecondary else NuvioColors.TextTertiary)
                }
                NuvioButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, stringResource(R.string.cd_edit), tint = NuvioColors.TextSecondary)
                }
                NuvioButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, stringResource(R.string.cd_delete), tint = NuvioColors.TextSecondary)
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun FolderEditorContent(
    viewModel: CollectionEditorViewModel,
    uiState: CollectionEditorUiState
) {
    val folder = uiState.editingFolder ?: return

    if (uiState.showCatalogPicker) {
        CatalogPickerContent(
            catalogs = uiState.availableCatalogs,
            alreadyAdded = folder.sources,
            onToggle = { viewModel.toggleCatalogSource(it) },
            onBack = { viewModel.hideCatalogPicker() }
        )
        return
    }

    if (uiState.showTmdbSourcePicker) {
        TmdbSourcePickerContent(
            uiState = uiState,
            presets = viewModel.tmdbPresets(),
            onModeChange = { viewModel.setTmdbBuilderMode(it) },
            onInputChange = { viewModel.setTmdbInput(it) },
            onTitleChange = { viewModel.setTmdbTitleInput(it) },
            onMediaTypeChange = { viewModel.setTmdbMediaType(it) },
            onSortChange = { viewModel.setTmdbSortBy(it) },
            onFiltersChange = { viewModel.setTmdbFilters(it) },
            onSearchCompanies = { viewModel.searchTmdbCompanies() },
            onSearchCollections = { viewModel.searchTmdbCollections() },
            onAddSource = { viewModel.addTmdbSource(it) },
            onAddFromInput = { viewModel.addTmdbSourceFromInput() },
            onAddDiscover = { viewModel.addDiscoverSource() },
            onBack = { viewModel.hideTmdbSourcePicker() }
        )
        return
    }

    if (uiState.showEmojiPicker) {
        EmojiPickerContent(
            selectedEmoji = folder.coverEmoji,
            onSelect = { emoji ->
                viewModel.updateFolderCoverEmoji(emoji)
                viewModel.hideEmojiPicker()
            },
            onBack = { viewModel.hideEmojiPicker() }
        )
        return
    }

    val genrePickerIndex = uiState.genrePickerSourceIndex
    val genrePickerSource = genrePickerIndex?.let { folder.sources.getOrNull(it) as? AddonCatalogCollectionSource }
    val genrePickerCatalog = genrePickerSource?.let { source ->
        uiState.availableCatalogs.find {
            it.addonId == source.addonId && it.type == source.type && it.catalogId == source.catalogId
        }
    }

    if (
        genrePickerIndex != null &&
        genrePickerSource != null &&
        genrePickerCatalog != null &&
        genrePickerCatalog.genreOptions.isNotEmpty()
    ) {
        GenrePickerContent(
            title = genrePickerCatalog.catalogName,
            selectedGenre = genrePickerSource.genre,
            genreOptions = genrePickerCatalog.genreOptions,
            allowAll = !genrePickerCatalog.genreRequired,
            onSelect = { genre ->
                viewModel.updateCatalogSourceGenre(genrePickerIndex, genre)
                viewModel.hideGenrePicker()
            },
            onBack = { viewModel.hideGenrePicker() }
        )
        return
    }

    val titleFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        repeat(5) { androidx.compose.runtime.withFrameNanos { } }
        try { titleFocusRequester.requestFocus() } catch (_: Exception) {}
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 48.dp, start = 48.dp, end = 48.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.collections_editor_edit_folder),
                style = MaterialTheme.typography.headlineMedium,
                color = NuvioColors.TextPrimary
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NuvioButton(onClick = { viewModel.cancelFolderEdit() }) {
                    Text(stringResource(R.string.collections_cancel))
                }
                val canSaveFolder = (uiState.editingFolder?.sources?.isNotEmpty() == true)
                NuvioButton(onClick = { viewModel.saveFolderEdit() }, enabled = canSaveFolder) {
                    Text(stringResource(R.string.collections_editor_save))
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        val catalogFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }
        var pendingFocusIndex by remember { mutableStateOf(-1) }

        LaunchedEffect(pendingFocusIndex, folder.sources.size) {
            if (pendingFocusIndex >= 0) {
                val targetIndex = pendingFocusIndex.coerceAtMost(folder.sources.lastIndex)
                if (targetIndex >= 0) {
                    val targetSource = folder.sources[targetIndex]
                    val targetKey = collectionSourceKey(targetSource)
                    repeat(3) { androidx.compose.runtime.withFrameNanos { } }
                    try { catalogFocusRequesters[targetKey]?.requestFocus() } catch (_: Exception) {}
                }
                pendingFocusIndex = -1
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 4.dp, end = 4.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text(stringResource(R.string.collections_editor_folder_title), style = MaterialTheme.typography.labelLarge, color = NuvioColors.TextSecondary)
                Spacer(modifier = Modifier.height(8.dp))
                NuvioTextField(
                    value = folder.title,
                    onValueChange = { viewModel.updateFolderTitle(it) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = stringResource(R.string.collections_editor_placeholder_folder),
                    focusRequester = titleFocusRequester
                )
            }

            item {
                val hasEmoji = !folder.coverEmoji.isNullOrBlank()
                val coverMode = when {
                    folder.coverImageUrl != null -> "image"
                    hasEmoji -> "emoji"
                    else -> "none"
                }

                Text(stringResource(R.string.collections_editor_cover), style = MaterialTheme.typography.labelLarge, color = NuvioColors.TextSecondary)
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = { viewModel.clearFolderCover() },
                        colors = ButtonDefaults.colors(
                            containerColor = if (coverMode == "none") NuvioColors.Secondary.copy(alpha = 0.3f) else NuvioColors.BackgroundCard,
                            contentColor = if (coverMode == "none") NuvioColors.Secondary else NuvioColors.TextSecondary,
                            focusedContainerColor = NuvioColors.FocusBackground,
                            focusedContentColor = NuvioColors.Primary
                        ),
                        border = ButtonDefaults.border(
                            border = if (coverMode == "none") Border(
                                border = BorderStroke(2.dp, NuvioColors.Secondary),
                                shape = RoundedCornerShape(12.dp)
                            ) else Border.None,
                            focusedBorder = Border(
                                border = BorderStroke(2.dp, NuvioColors.FocusRing),
                                shape = RoundedCornerShape(12.dp)
                            )
                        ),
                        shape = ButtonDefaults.shape(RoundedCornerShape(12.dp))
                    ) { Text(stringResource(R.string.collections_editor_cover_none)) }

                    Button(
                        onClick = { viewModel.showEmojiPicker() },
                        colors = ButtonDefaults.colors(
                            containerColor = if (coverMode == "emoji") NuvioColors.Secondary.copy(alpha = 0.3f) else NuvioColors.BackgroundCard,
                            contentColor = if (coverMode == "emoji") NuvioColors.Secondary else NuvioColors.TextSecondary,
                            focusedContainerColor = NuvioColors.FocusBackground,
                            focusedContentColor = NuvioColors.Primary
                        ),
                        border = ButtonDefaults.border(
                            border = if (coverMode == "emoji") Border(
                                border = BorderStroke(2.dp, NuvioColors.Secondary),
                                shape = RoundedCornerShape(12.dp)
                            ) else Border.None,
                            focusedBorder = Border(
                                border = BorderStroke(2.dp, NuvioColors.FocusRing),
                                shape = RoundedCornerShape(12.dp)
                            )
                        ),
                        shape = ButtonDefaults.shape(RoundedCornerShape(12.dp))
                    ) {
                        if (hasEmoji) {
                            Text("${folder.coverEmoji}  ${stringResource(R.string.collections_editor_cover_emoji)}")
                        } else {
                            Text(stringResource(R.string.collections_editor_cover_emoji))
                        }
                    }

                    Button(
                        onClick = { viewModel.switchToImageMode() },
                        colors = ButtonDefaults.colors(
                            containerColor = if (coverMode == "image") NuvioColors.Secondary.copy(alpha = 0.3f) else NuvioColors.BackgroundCard,
                            contentColor = if (coverMode == "image") NuvioColors.Secondary else NuvioColors.TextSecondary,
                            focusedContainerColor = NuvioColors.FocusBackground,
                            focusedContentColor = NuvioColors.Primary
                        ),
                        border = ButtonDefaults.border(
                            border = if (coverMode == "image") Border(
                                border = BorderStroke(2.dp, NuvioColors.Secondary),
                                shape = RoundedCornerShape(12.dp)
                            ) else Border.None,
                            focusedBorder = Border(
                                border = BorderStroke(2.dp, NuvioColors.FocusRing),
                                shape = RoundedCornerShape(12.dp)
                            )
                        ),
                        shape = ButtonDefaults.shape(RoundedCornerShape(12.dp))
                    ) { Text(stringResource(R.string.collections_editor_cover_image_url)) }
                }

                if (coverMode == "image") {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        NuvioTextField(
                            value = folder.coverImageUrl ?: "",
                            onValueChange = { viewModel.updateFolderCoverImage(it) },
                            modifier = Modifier.weight(1f),
                            placeholder = "https://..."
                        )
                        if (!folder.coverImageUrl.isNullOrBlank()) {
                            Card(
                                onClick = {},
                                modifier = Modifier
                                    .width(56.dp)
                                    .height(56.dp),
                                shape = CardDefaults.shape(RoundedCornerShape(12.dp)),
                                colors = CardDefaults.colors(containerColor = NuvioColors.BackgroundCard),
                                scale = CardDefaults.scale(focusedScale = 1f)
                            ) {
                                AsyncImage(
                                    model = folder.coverImageUrl,
                                    contentDescription = stringResource(R.string.cd_preview),
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(RoundedCornerShape(12.dp)),
                                    contentScale = ContentScale.FillBounds
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                Text(stringResource(R.string.collections_editor_focus_gif), style = MaterialTheme.typography.labelLarge, color = NuvioColors.TextSecondary)
                Spacer(modifier = Modifier.height(8.dp))
                NuvioTextField(
                    value = folder.focusGifUrl.orEmpty(),
                    onValueChange = { viewModel.updateFolderFocusGifUrl(it) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = stringResource(R.string.collections_editor_placeholder_gif)
                )

                Spacer(modifier = Modifier.height(12.dp))
                Card(
                    onClick = { viewModel.updateFolderFocusGifEnabled(!folder.focusGifEnabled) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.colors(
                        containerColor = NuvioColors.BackgroundCard,
                        focusedContainerColor = NuvioColors.FocusBackground
                    ),
                    border = CardDefaults.border(
                        focusedBorder = Border(
                            border = BorderStroke(2.dp, NuvioColors.FocusRing),
                            shape = RoundedCornerShape(12.dp)
                        )
                    ),
                    scale = CardDefaults.scale(focusedScale = 1f),
                    shape = CardDefaults.shape(RoundedCornerShape(12.dp))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(stringResource(R.string.collections_editor_play_gif), style = MaterialTheme.typography.bodyLarge, color = NuvioColors.TextPrimary)
                        Switch(
                            checked = folder.focusGifEnabled,
                            onCheckedChange = { viewModel.updateFolderFocusGifEnabled(it) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                Text(stringResource(R.string.collections_editor_hero_backdrop), style = MaterialTheme.typography.labelLarge, color = NuvioColors.TextSecondary)
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    NuvioTextField(
                        value = folder.heroBackdropUrl.orEmpty(),
                        onValueChange = { viewModel.updateFolderHeroBackdropUrl(it) },
                        modifier = Modifier.weight(1f),
                        placeholder = stringResource(R.string.collections_editor_placeholder_hero_backdrop)
                    )
                    if (!folder.heroBackdropUrl.isNullOrBlank()) {
                        Card(
                            onClick = {},
                            modifier = Modifier
                                .width(56.dp)
                                .height(56.dp),
                            shape = CardDefaults.shape(RoundedCornerShape(12.dp)),
                            colors = CardDefaults.colors(containerColor = NuvioColors.BackgroundCard),
                            scale = CardDefaults.scale(focusedScale = 1f)
                        ) {
                            AsyncImage(
                                model = folder.heroBackdropUrl,
                                contentDescription = stringResource(R.string.cd_preview),
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(12.dp)),
                                contentScale = ContentScale.FillBounds
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                Text(stringResource(R.string.collections_editor_title_logo), style = MaterialTheme.typography.labelLarge, color = NuvioColors.TextSecondary)
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    NuvioTextField(
                        value = folder.titleLogoUrl.orEmpty(),
                        onValueChange = { viewModel.updateFolderTitleLogoUrl(it) },
                        modifier = Modifier.weight(1f),
                        placeholder = stringResource(R.string.collections_editor_placeholder_title_logo)
                    )
                    if (!folder.titleLogoUrl.isNullOrBlank()) {
                        Card(
                            onClick = {},
                            modifier = Modifier
                                .width(100.dp)
                                .height(56.dp),
                            shape = CardDefaults.shape(RoundedCornerShape(12.dp)),
                            colors = CardDefaults.colors(containerColor = NuvioColors.BackgroundCard),
                            scale = CardDefaults.scale(focusedScale = 1f)
                        ) {
                            AsyncImage(
                                model = folder.titleLogoUrl,
                                contentDescription = stringResource(R.string.cd_preview),
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(12.dp)),
                                contentScale = ContentScale.Fit
                            )
                        }
                    }
                }
            }

            item {
                Text(stringResource(R.string.collections_editor_tile_shape), style = MaterialTheme.typography.labelLarge, color = NuvioColors.TextSecondary)
                Spacer(modifier = Modifier.height(8.dp))
                val shapeFocusRequesters = remember { PosterShape.entries.associateWith { FocusRequester() } }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.focusRestorer {
                        shapeFocusRequesters[folder.tileShape] ?: FocusRequester.Default
                    }
                ) {
                    PosterShape.entries.forEach { shape ->
                        val label = when (shape) {
                            PosterShape.POSTER -> stringResource(R.string.collections_editor_shape_poster)
                            PosterShape.LANDSCAPE -> stringResource(R.string.collections_editor_shape_wide)
                            PosterShape.SQUARE -> stringResource(R.string.collections_editor_shape_square)
                        }
                        val isSelected = folder.tileShape == shape
                        Button(
                            onClick = { viewModel.updateFolderTileShape(shape) },
                            modifier = Modifier.focusRequester(shapeFocusRequesters[shape]!!),
                            colors = ButtonDefaults.colors(
                                containerColor = if (isSelected) NuvioColors.Secondary.copy(alpha = 0.3f) else NuvioColors.BackgroundCard,
                                contentColor = if (isSelected) NuvioColors.Secondary else NuvioColors.TextSecondary,
                                focusedContainerColor = NuvioColors.FocusBackground,
                                focusedContentColor = NuvioColors.Primary
                            ),
                            border = ButtonDefaults.border(
                                border = if (isSelected) Border(
                                    border = BorderStroke(2.dp, NuvioColors.Secondary),
                                    shape = RoundedCornerShape(12.dp)
                                ) else Border.None,
                                focusedBorder = Border(
                                    border = BorderStroke(2.dp, NuvioColors.FocusRing),
                                    shape = RoundedCornerShape(12.dp)
                                )
                            ),
                            shape = ButtonDefaults.shape(RoundedCornerShape(12.dp))
                        ) {
                            Text(label)
                        }
                    }
                }
            }

            item {
                Card(
                    onClick = { viewModel.updateFolderHideTitle(!folder.hideTitle) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.colors(
                        containerColor = NuvioColors.BackgroundCard,
                        focusedContainerColor = NuvioColors.FocusBackground
                    ),
                    border = CardDefaults.border(
                        focusedBorder = Border(
                            border = BorderStroke(2.dp, NuvioColors.FocusRing),
                            shape = RoundedCornerShape(12.dp)
                        )
                    ),
                    shape = CardDefaults.shape(RoundedCornerShape(12.dp)),
                    scale = CardDefaults.scale(focusedScale = 1.02f)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.collections_editor_hide_title),
                                style = MaterialTheme.typography.titleMedium,
                                color = NuvioColors.TextPrimary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.collections_editor_hide_title_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = NuvioColors.TextSecondary
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Switch(
                            checked = folder.hideTitle,
                            onCheckedChange = { viewModel.updateFolderHideTitle(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = NuvioColors.Secondary,
                                checkedTrackColor = NuvioColors.Secondary.copy(alpha = 0.3f),
                                uncheckedThumbColor = NuvioColors.TextSecondary,
                                uncheckedTrackColor = NuvioColors.BackgroundCard
                            )
                        )
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.collections_editor_catalogs), style = MaterialTheme.typography.labelLarge, color = NuvioColors.TextSecondary)
                    Text(
                        "${folder.sources.size} ${stringResource(R.string.collections_editor_sources).lowercase()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioColors.TextTertiary
                    )
                }
            }

            itemsIndexed(
                items = folder.sources,
                key = { _, source -> collectionSourceKey(source) }
            ) { index, source ->
                val addonSource = source as? AddonCatalogCollectionSource
                val tmdbSource = source as? TmdbCollectionSource
                val catalog = addonSource?.let { addon ->
                    uiState.availableCatalogs.find {
                        it.addonId == addon.addonId && it.type == addon.type && it.catalogId == addon.catalogId
                    }
                }
                val isMissing = addonSource != null && catalog == null
                val sourceKey = collectionSourceKey(source)
                val removeFocusRequester = catalogFocusRequesters.getOrPut(sourceKey) { FocusRequester() }
                val genreLabel = addonSource?.genre ?: if (catalog?.genreRequired == true) {
                    stringResource(R.string.collections_editor_select_genre)
                } else {
                    stringResource(R.string.collections_editor_all_genres)
                }
                val hasGenreOptions = addonSource != null && catalog?.genreOptions?.isNotEmpty() == true
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    colors = SurfaceDefaults.colors(containerColor = NuvioColors.BackgroundCard),
                    border = if (isMissing) Border(
                        border = BorderStroke(1.dp, NuvioColors.Error.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(12.dp)
                    ) else Border.None,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = catalog?.catalogName?.replaceFirstChar { it.uppercase() }
                                    ?: tmdbSource?.title
                                    ?: addonSource?.catalogId
                                    ?: stringResource(R.string.collections_editor_source),
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isMissing) NuvioColors.Error else NuvioColors.TextPrimary
                            )
                            Text(
                                text = when {
                                    isMissing && addonSource != null -> stringResource(R.string.collections_editor_addon_missing, addonSource.addonId)
                                    addonSource != null && catalog != null -> "${addonSource.type} - ${catalog.addonName}"
                                    tmdbSource != null -> tmdbSourceSubtitle(tmdbSource)
                                    else -> stringResource(R.string.collections_editor_source)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isMissing) NuvioColors.Error.copy(alpha = 0.7f) else NuvioColors.TextTertiary
                            )
                            if (hasGenreOptions) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .clip(CircleShape)
                                            .background(NuvioColors.BackgroundElevated)
                                            .padding(horizontal = 10.dp, vertical = 4.dp)
                                    ) {
                                        Text(
                                            text = stringResource(R.string.collections_editor_genre_filter),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = NuvioColors.TextSecondary
                                        )
                                    }
                                    Text(
                                        text = genreLabel,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = NuvioColors.TextSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Button(
                                        onClick = { viewModel.showGenrePicker(index) },
                                        colors = ButtonDefaults.colors(
                                            containerColor = NuvioColors.BackgroundElevated,
                                            contentColor = NuvioColors.TextSecondary,
                                            focusedContainerColor = NuvioColors.FocusBackground,
                                            focusedContentColor = NuvioColors.Primary
                                        ),
                                        border = ButtonDefaults.border(
                                            focusedBorder = Border(
                                                border = BorderStroke(2.dp, NuvioColors.FocusRing),
                                                shape = RoundedCornerShape(12.dp)
                                            )
                                        ),
                                        shape = ButtonDefaults.shape(RoundedCornerShape(12.dp))
                                    ) {
                                        Text(stringResource(R.string.collections_editor_choose_genre))
                                    }
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            Button(
                                onClick = { viewModel.moveCatalogSourceUp(index) },
                                colors = ButtonDefaults.colors(
                                    containerColor = NuvioColors.BackgroundCard,
                                    contentColor = NuvioColors.TextSecondary,
                                    focusedContainerColor = NuvioColors.FocusBackground,
                                    focusedContentColor = NuvioColors.TextPrimary
                                ),
                                border = ButtonDefaults.border(
                                    focusedBorder = Border(
                                        border = BorderStroke(2.dp, NuvioColors.FocusRing),
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                ),
                                shape = ButtonDefaults.shape(RoundedCornerShape(12.dp))
                            ) {
                                Icon(Icons.Default.KeyboardArrowUp, stringResource(R.string.cd_move_up), tint = if (index > 0) NuvioColors.TextSecondary else NuvioColors.TextTertiary)
                            }
                            Button(
                                onClick = { viewModel.moveCatalogSourceDown(index) },
                                colors = ButtonDefaults.colors(
                                    containerColor = NuvioColors.BackgroundCard,
                                    contentColor = NuvioColors.TextSecondary,
                                    focusedContainerColor = NuvioColors.FocusBackground,
                                    focusedContentColor = NuvioColors.TextPrimary
                                ),
                                border = ButtonDefaults.border(
                                    focusedBorder = Border(
                                        border = BorderStroke(2.dp, NuvioColors.FocusRing),
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                ),
                                shape = ButtonDefaults.shape(RoundedCornerShape(12.dp))
                            ) {
                                Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.cd_move_down), tint = if (index < folder.sources.size - 1) NuvioColors.TextSecondary else NuvioColors.TextTertiary)
                            }
                            Button(
                                onClick = {
                                    pendingFocusIndex = index
                                    viewModel.removeCatalogSource(index)
                                },
                                modifier = Modifier.focusRequester(removeFocusRequester),
                                colors = ButtonDefaults.colors(
                                    containerColor = NuvioColors.BackgroundCard,
                                    contentColor = NuvioColors.TextSecondary,
                                    focusedContainerColor = NuvioColors.FocusBackground,
                                    focusedContentColor = NuvioColors.Error
                                ),
                                border = ButtonDefaults.border(
                                    focusedBorder = Border(
                                        border = BorderStroke(2.dp, NuvioColors.FocusRing),
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                ),
                                shape = ButtonDefaults.shape(RoundedCornerShape(12.dp))
                            ) {
                                Icon(Icons.Default.Close, stringResource(R.string.cd_remove))
                            }
                        }
                    }
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NuvioButton(onClick = { viewModel.showCatalogPicker() }) {
                        Icon(Icons.Default.Add, stringResource(R.string.cd_add))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.collections_editor_add_catalog))
                    }
                    NuvioButton(onClick = { viewModel.showTmdbSourcePicker() }) {
                        Icon(Icons.Default.Add, stringResource(R.string.cd_add))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.collections_editor_add_tmdb_source))
                    }
                }
            }
        }
    }
}

private fun collectionSourceKey(source: CollectionSource): String {
    return when (source) {
        is AddonCatalogCollectionSource -> "addon_${source.addonId}_${source.type}_${source.catalogId}_${source.genre.orEmpty()}"
        is TmdbCollectionSource -> "tmdb_${source.sourceType}_${source.tmdbId}_${source.mediaType}_${source.sortBy}_${source.filters.hashCode()}"
    }
}

private fun tmdbSourceSubtitle(source: TmdbCollectionSource): String {
    val type = when (source.sourceType) {
        TmdbCollectionSourceType.LIST -> "TMDB List"
        TmdbCollectionSourceType.COLLECTION -> "TMDB Collection"
        TmdbCollectionSourceType.COMPANY -> "Production"
        TmdbCollectionSourceType.NETWORK -> "Network"
        TmdbCollectionSourceType.DISCOVER -> "TMDB Discover"
    }
    val media = when (source.mediaType) {
        TmdbCollectionMediaType.MOVIE -> "Movies"
        TmdbCollectionMediaType.TV -> "Series"
    }
    return listOf(type, media, source.sortBy).joinToString(" • ")
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CatalogPickerContent(
    catalogs: List<AvailableCatalog>,
    alreadyAdded: List<CollectionSource>,
    onToggle: (AvailableCatalog) -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 48.dp, start = 48.dp, end = 48.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.collections_editor_select_catalogs),
                style = MaterialTheme.typography.headlineMedium,
                color = NuvioColors.TextPrimary
            )
            NuvioButton(onClick = onBack) { Text(stringResource(R.string.collections_editor_done)) }
        }

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(
                items = catalogs,
                key = { _, c -> "${c.addonId}_${c.type}_${c.catalogId}" }
            ) { _, catalog ->
                val isAdded = alreadyAdded.any {
                    it is AddonCatalogCollectionSource && it.addonId == catalog.addonId && it.type == catalog.type && it.catalogId == catalog.catalogId
                }
                Card(
                    onClick = { onToggle(catalog) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.colors(
                        containerColor = if (isAdded) NuvioColors.Secondary.copy(alpha = 0.15f) else NuvioColors.BackgroundCard,
                        focusedContainerColor = NuvioColors.FocusBackground
                    ),
                    border = CardDefaults.border(
                        border = if (isAdded) Border(
                            border = BorderStroke(1.dp, NuvioColors.Secondary.copy(alpha = 0.5f)),
                            shape = RoundedCornerShape(12.dp)
                        ) else Border.None,
                        focusedBorder = Border(
                            border = BorderStroke(2.dp, NuvioColors.FocusRing),
                            shape = RoundedCornerShape(12.dp)
                        )
                    ),
                    shape = CardDefaults.shape(RoundedCornerShape(12.dp)),
                    scale = CardDefaults.scale(focusedScale = 1.01f)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = catalog.catalogName.replaceFirstChar { it.uppercase() },
                                style = MaterialTheme.typography.titleSmall,
                                color = NuvioColors.TextPrimary
                            )
                            val supportingGenreText = when {
                                catalog.genreRequired -> stringResource(R.string.collections_editor_genre_required)
                                catalog.genreOptions.isNotEmpty() -> stringResource(R.string.collections_editor_genre_optional)
                                else -> null
                            }
                            Text(
                                text = listOfNotNull("${catalog.type} - ${catalog.addonName}", supportingGenreText).joinToString(" • "),
                                style = MaterialTheme.typography.bodySmall,
                                color = NuvioColors.TextTertiary
                            )
                        }
                        if (isAdded) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Remove",
                                tint = NuvioColors.TextSecondary
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = stringResource(R.string.cd_add),
                                tint = NuvioColors.TextTertiary
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TmdbSourcePickerContent(
    uiState: CollectionEditorUiState,
    presets: List<TmdbPresetSource>,
    onModeChange: (TmdbBuilderMode) -> Unit,
    onInputChange: (String) -> Unit,
    onTitleChange: (String) -> Unit,
    onMediaTypeChange: (TmdbCollectionMediaType) -> Unit,
    onSortChange: (String) -> Unit,
    onFiltersChange: (TmdbCollectionFilters) -> Unit,
    onSearchCompanies: () -> Unit,
    onSearchCollections: () -> Unit,
    onAddSource: (TmdbCollectionSource) -> Unit,
    onAddFromInput: () -> Unit,
    onAddDiscover: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 48.dp, start = 48.dp, end = 48.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.collections_editor_tmdb_sources),
                style = MaterialTheme.typography.headlineMedium,
                color = NuvioColors.TextPrimary
            )
            NuvioButton(onClick = onBack) { Text(stringResource(R.string.collections_editor_done)) }
        }

        Spacer(modifier = Modifier.height(16.dp))

        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(TmdbBuilderMode.values().toList()) { mode ->
                val selected = uiState.tmdbBuilderMode == mode
                Button(
                    onClick = { onModeChange(mode) },
                    colors = ButtonDefaults.colors(
                        containerColor = if (selected) NuvioColors.Secondary.copy(alpha = 0.3f) else NuvioColors.BackgroundCard,
                        contentColor = if (selected) NuvioColors.Secondary else NuvioColors.TextSecondary,
                        focusedContainerColor = NuvioColors.FocusBackground,
                        focusedContentColor = NuvioColors.Primary
                    ),
                    shape = ButtonDefaults.shape(RoundedCornerShape(12.dp))
                ) {
                    Text(tmdbModeLabel(mode))
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        uiState.tmdbSearchError?.takeIf { it.isNotBlank() }?.let { error ->
            Text(error, color = NuvioColors.Error, style = MaterialTheme.typography.bodySmall)
            Spacer(modifier = Modifier.height(8.dp))
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                TmdbModeHelp(mode = uiState.tmdbBuilderMode)
            }
            when (uiState.tmdbBuilderMode) {
                TmdbBuilderMode.PRESETS -> {
                    items(presets) { preset ->
                        TmdbPickerCard(
                            title = preset.title,
                            subtitle = tmdbSourceSubtitle(preset.source),
                            onClick = { onAddSource(preset.source) }
                        )
                    }
                }
                TmdbBuilderMode.LIST,
                TmdbBuilderMode.NETWORK -> {
                    item {
                        TmdbBasicSourceForm(
                            uiState = uiState,
                            onInputChange = onInputChange,
                            onTitleChange = onTitleChange,
                            onMediaTypeChange = onMediaTypeChange,
                            onSortChange = onSortChange,
                            onAdd = onAddFromInput,
                            lockTv = uiState.tmdbBuilderMode == TmdbBuilderMode.NETWORK
                        )
                    }
                }
                TmdbBuilderMode.PRODUCTION -> {
                    item {
                        TmdbBasicSourceForm(
                            uiState = uiState,
                            onInputChange = onInputChange,
                            onTitleChange = onTitleChange,
                            onMediaTypeChange = onMediaTypeChange,
                            onSortChange = onSortChange,
                            onAdd = onAddFromInput,
                            onSearch = onSearchCompanies
                        )
                    }
                    items(uiState.tmdbCompanyResults) { result ->
                        val title = result.name ?: "TMDB Company ${result.id}"
                        TmdbPickerCard(
                            title = title,
                            subtitle = listOfNotNull("Production", result.originCountry).joinToString(" • "),
                            onClick = {
                                onAddSource(
                                    TmdbCollectionSource(
                                        sourceType = TmdbCollectionSourceType.COMPANY,
                                        title = title,
                                        tmdbId = result.id,
                                        mediaType = uiState.tmdbMediaType,
                                        sortBy = uiState.tmdbSortBy,
                                        filters = uiState.tmdbFilters
                                    )
                                )
                            }
                        )
                    }
                }
                TmdbBuilderMode.COLLECTION -> {
                    item {
                        TmdbBasicSourceForm(
                            uiState = uiState,
                            onInputChange = onInputChange,
                            onTitleChange = onTitleChange,
                            onMediaTypeChange = onMediaTypeChange,
                            onSortChange = onSortChange,
                            onAdd = onAddFromInput,
                            onSearch = onSearchCollections,
                            lockMovie = true
                        )
                    }
                    items(uiState.tmdbCollectionResults) { result ->
                        val title = result.name ?: "TMDB Collection ${result.id}"
                        TmdbPickerCard(
                            title = title,
                            subtitle = stringResource(R.string.collections_editor_tmdb_collection),
                            onClick = {
                                onAddSource(
                                    TmdbCollectionSource(
                                        sourceType = TmdbCollectionSourceType.COLLECTION,
                                        title = title,
                                        tmdbId = result.id,
                                        mediaType = TmdbCollectionMediaType.MOVIE,
                                        sortBy = uiState.tmdbSortBy
                                    )
                                )
                            }
                        )
                    }
                }
                TmdbBuilderMode.DISCOVER -> {
                    item {
                        TmdbDiscoverForm(
                            uiState = uiState,
                            onTitleChange = onTitleChange,
                            onMediaTypeChange = onMediaTypeChange,
                            onSortChange = onSortChange,
                            onFiltersChange = onFiltersChange,
                            onAdd = onAddDiscover
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TmdbBasicSourceForm(
    uiState: CollectionEditorUiState,
    onInputChange: (String) -> Unit,
    onTitleChange: (String) -> Unit,
    onMediaTypeChange: (TmdbCollectionMediaType) -> Unit,
    onSortChange: (String) -> Unit,
    onAdd: () -> Unit,
    onSearch: (() -> Unit)? = null,
    lockTv: Boolean = false,
    lockMovie: Boolean = false
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TmdbLabeledField(
            label = when (uiState.tmdbBuilderMode) {
                TmdbBuilderMode.LIST -> stringResource(R.string.collections_editor_tmdb_public_list)
                TmdbBuilderMode.NETWORK -> stringResource(R.string.collections_editor_tmdb_network_id)
                TmdbBuilderMode.COLLECTION -> stringResource(R.string.collections_editor_tmdb_collection_id)
                TmdbBuilderMode.PRODUCTION -> stringResource(R.string.collections_editor_tmdb_company_search)
                else -> stringResource(R.string.collections_editor_tmdb_id_or_url)
            },
            value = uiState.tmdbInput,
            onValueChange = onInputChange,
            placeholder = when (uiState.tmdbBuilderMode) {
                TmdbBuilderMode.LIST -> "https://www.themoviedb.org/list/8504994 or 8504994"
                TmdbBuilderMode.NETWORK -> "213 for Netflix, 49 for HBO, 2739 for Disney+"
                TmdbBuilderMode.COLLECTION -> "10 for Star Wars Collection"
                TmdbBuilderMode.PRODUCTION -> "Marvel Studios, Pixar, Warner Bros."
                else -> stringResource(R.string.collections_editor_tmdb_id_or_url)
            },
            helper = when (uiState.tmdbBuilderMode) {
                TmdbBuilderMode.PRODUCTION -> stringResource(R.string.collections_editor_tmdb_search_helper)
                TmdbBuilderMode.COLLECTION -> stringResource(R.string.collections_editor_tmdb_collection_helper)
                TmdbBuilderMode.NETWORK -> stringResource(R.string.collections_editor_tmdb_network_helper)
                TmdbBuilderMode.LIST -> stringResource(R.string.collections_editor_tmdb_list_helper)
                else -> ""
            }
        )
        TmdbLabeledField(
            label = stringResource(R.string.collections_editor_tmdb_display_title),
            value = uiState.tmdbTitleInput,
            onValueChange = onTitleChange,
            placeholder = "Marvel Movies, Netflix Originals, Pixar",
            helper = stringResource(R.string.collections_editor_tmdb_title_helper)
        )
        TmdbMediaSortControls(
            mediaType = uiState.tmdbMediaType,
            sortBy = uiState.tmdbSortBy,
            onMediaTypeChange = onMediaTypeChange,
            onSortChange = onSortChange,
            lockTv = lockTv,
            lockMovie = lockMovie
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            onSearch?.let {
                NuvioButton(onClick = it) { Text(stringResource(R.string.collections_editor_tmdb_search)) }
            }
            NuvioButton(onClick = onAdd) { Text(stringResource(R.string.collections_editor_add_source)) }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TmdbDiscoverForm(
    uiState: CollectionEditorUiState,
    onTitleChange: (String) -> Unit,
    onMediaTypeChange: (TmdbCollectionMediaType) -> Unit,
    onSortChange: (String) -> Unit,
    onFiltersChange: (TmdbCollectionFilters) -> Unit,
    onAdd: () -> Unit
) {
    val filters = uiState.tmdbFilters
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TmdbLabeledField(
            label = stringResource(R.string.collections_editor_tmdb_display_title),
            value = uiState.tmdbTitleInput,
            onValueChange = onTitleChange,
            placeholder = "Best Action Movies, Korean Dramas, 2024 Animation",
            helper = stringResource(R.string.collections_editor_tmdb_title_helper)
        )
        TmdbMediaSortControls(
            mediaType = uiState.tmdbMediaType,
            sortBy = uiState.tmdbSortBy,
            onMediaTypeChange = onMediaTypeChange,
            onSortChange = onSortChange
        )
        TmdbQuickChips(
            label = stringResource(R.string.collections_editor_tmdb_quick_genres),
            chips = tmdbGenreQuickChips(uiState.tmdbMediaType),
            onSelect = { onFiltersChange(filters.copy(withGenres = it)) }
        )
        TmdbFilterField(
            label = stringResource(R.string.collections_editor_tmdb_genres),
            helper = stringResource(R.string.collections_editor_tmdb_genres_helper),
            placeholder = if (uiState.tmdbMediaType == TmdbCollectionMediaType.MOVIE) "28,12" else "18,35",
            value = filters.withGenres
        ) {
            onFiltersChange(filters.copy(withGenres = it.ifBlank { null }))
        }
        TmdbFilterField(
            label = stringResource(R.string.collections_editor_tmdb_date_from),
            helper = stringResource(R.string.collections_editor_tmdb_date_helper),
            placeholder = "2020-01-01",
            value = filters.releaseDateGte
        ) {
            onFiltersChange(filters.copy(releaseDateGte = it.ifBlank { null }))
        }
        TmdbFilterField(
            label = stringResource(R.string.collections_editor_tmdb_date_to),
            helper = stringResource(R.string.collections_editor_tmdb_date_helper),
            placeholder = "2024-12-31",
            value = filters.releaseDateLte
        ) {
            onFiltersChange(filters.copy(releaseDateLte = it.ifBlank { null }))
        }
        TmdbFilterField(
            label = stringResource(R.string.collections_editor_tmdb_rating_min),
            helper = stringResource(R.string.collections_editor_tmdb_rating_helper),
            placeholder = "7.0",
            value = filters.voteAverageGte?.toString()
        ) {
            onFiltersChange(filters.copy(voteAverageGte = it.toDoubleOrNull()))
        }
        TmdbFilterField(
            label = stringResource(R.string.collections_editor_tmdb_rating_max),
            helper = stringResource(R.string.collections_editor_tmdb_rating_helper),
            placeholder = "10",
            value = filters.voteAverageLte?.toString()
        ) {
            onFiltersChange(filters.copy(voteAverageLte = it.toDoubleOrNull()))
        }
        TmdbFilterField(
            label = stringResource(R.string.collections_editor_tmdb_votes_min),
            helper = stringResource(R.string.collections_editor_tmdb_votes_helper),
            placeholder = "100",
            value = filters.voteCountGte?.toString()
        ) {
            onFiltersChange(filters.copy(voteCountGte = it.toIntOrNull()))
        }
        TmdbQuickChips(
            label = stringResource(R.string.collections_editor_tmdb_quick_languages),
            chips = listOf("English" to "en", "Korean" to "ko", "Japanese" to "ja", "Hindi" to "hi", "Spanish" to "es"),
            onSelect = { onFiltersChange(filters.copy(withOriginalLanguage = it)) }
        )
        TmdbFilterField(
            label = stringResource(R.string.collections_editor_tmdb_language),
            helper = stringResource(R.string.collections_editor_tmdb_language_helper),
            placeholder = "en, ko, ja, hi",
            value = filters.withOriginalLanguage
        ) {
            onFiltersChange(filters.copy(withOriginalLanguage = it.ifBlank { null }))
        }
        TmdbQuickChips(
            label = stringResource(R.string.collections_editor_tmdb_quick_countries),
            chips = listOf("United States" to "US", "Korea" to "KR", "Japan" to "JP", "India" to "IN", "United Kingdom" to "GB"),
            onSelect = { onFiltersChange(filters.copy(withOriginCountry = it)) }
        )
        TmdbFilterField(
            label = stringResource(R.string.collections_editor_tmdb_country),
            helper = stringResource(R.string.collections_editor_tmdb_country_helper),
            placeholder = "US, KR, JP, IN",
            value = filters.withOriginCountry
        ) {
            onFiltersChange(filters.copy(withOriginCountry = it.ifBlank { null }))
        }
        TmdbQuickChips(
            label = stringResource(R.string.collections_editor_tmdb_quick_keywords),
            chips = listOf("Superhero" to "9715", "Based on Novel" to "818", "Time Travel" to "4379", "Space" to "9882"),
            onSelect = { onFiltersChange(filters.copy(withKeywords = it)) }
        )
        TmdbFilterField(
            label = stringResource(R.string.collections_editor_tmdb_keywords),
            helper = stringResource(R.string.collections_editor_tmdb_keywords_helper),
            placeholder = "9715 for superhero",
            value = filters.withKeywords
        ) {
            onFiltersChange(filters.copy(withKeywords = it.ifBlank { null }))
        }
        TmdbQuickChips(
            label = stringResource(R.string.collections_editor_tmdb_quick_companies),
            chips = listOf("Marvel" to "420", "Disney" to "2", "Pixar" to "3", "Lucasfilm" to "1", "Warner Bros." to "174"),
            onSelect = { onFiltersChange(filters.copy(withCompanies = it)) }
        )
        TmdbFilterField(
            label = stringResource(R.string.collections_editor_tmdb_companies),
            helper = stringResource(R.string.collections_editor_tmdb_companies_helper),
            placeholder = "420 for Marvel Studios",
            value = filters.withCompanies
        ) {
            onFiltersChange(filters.copy(withCompanies = it.ifBlank { null }))
        }
        TmdbQuickChips(
            label = stringResource(R.string.collections_editor_tmdb_quick_networks),
            chips = listOf("Netflix" to "213", "HBO" to "49", "Disney+" to "2739", "Prime Video" to "1024", "Hulu" to "453"),
            onSelect = { onFiltersChange(filters.copy(withNetworks = it)) }
        )
        TmdbFilterField(
            label = stringResource(R.string.collections_editor_tmdb_networks),
            helper = stringResource(R.string.collections_editor_tmdb_networks_helper),
            placeholder = "213 for Netflix",
            value = filters.withNetworks
        ) {
            onFiltersChange(filters.copy(withNetworks = it.ifBlank { null }))
        }
        TmdbFilterField(
            label = stringResource(R.string.collections_editor_tmdb_year),
            helper = stringResource(R.string.collections_editor_tmdb_year_helper),
            placeholder = "2024",
            value = filters.year?.toString()
        ) {
            onFiltersChange(filters.copy(year = it.toIntOrNull()))
        }
        NuvioButton(onClick = onAdd) { Text(stringResource(R.string.collections_editor_add_source)) }
    }
}

@Composable
private fun TmdbFilterField(
    label: String,
    helper: String,
    placeholder: String,
    value: String?,
    onValueChange: (String) -> Unit
) {
    TmdbLabeledField(
        label = label,
        value = value.orEmpty(),
        onValueChange = onValueChange,
        placeholder = placeholder,
        helper = helper
    )
}

@Composable
private fun TmdbLabeledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    helper: String
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = NuvioColors.TextPrimary)
        NuvioTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = placeholder
        )
        if (helper.isNotBlank()) {
            Text(helper, style = MaterialTheme.typography.bodySmall, color = NuvioColors.TextTertiary)
        }
    }
}

@Composable
private fun TmdbModeHelp(mode: TmdbBuilderMode) {
    val text = when (mode) {
        TmdbBuilderMode.PRESETS -> stringResource(R.string.collections_editor_tmdb_help_presets)
        TmdbBuilderMode.LIST -> stringResource(R.string.collections_editor_tmdb_help_list)
        TmdbBuilderMode.PRODUCTION -> stringResource(R.string.collections_editor_tmdb_help_production)
        TmdbBuilderMode.NETWORK -> stringResource(R.string.collections_editor_tmdb_help_network)
        TmdbBuilderMode.COLLECTION -> stringResource(R.string.collections_editor_tmdb_help_collection)
        TmdbBuilderMode.DISCOVER -> stringResource(R.string.collections_editor_tmdb_help_discover)
    }
    Text(text, style = MaterialTheme.typography.bodySmall, color = NuvioColors.TextSecondary)
}

@Composable
private fun TmdbQuickChips(
    label: String,
    chips: List<Pair<String, String>>,
    onSelect: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = NuvioColors.TextSecondary)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(chips) { (chipLabel, value) ->
                TmdbChoiceButton(
                    label = chipLabel,
                    selected = false,
                    onClick = { onSelect(value) }
                )
            }
        }
    }
}

private fun tmdbGenreQuickChips(mediaType: TmdbCollectionMediaType): List<Pair<String, String>> {
    return when (mediaType) {
        TmdbCollectionMediaType.MOVIE -> listOf(
            "Action" to "28",
            "Adventure" to "12",
            "Animation" to "16",
            "Comedy" to "35",
            "Horror" to "27",
            "Sci-Fi" to "878"
        )
        TmdbCollectionMediaType.TV -> listOf(
            "Drama" to "18",
            "Comedy" to "35",
            "Animation" to "16",
            "Crime" to "80",
            "Sci-Fi" to "10765",
            "Reality" to "10764"
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TmdbMediaSortControls(
    mediaType: TmdbCollectionMediaType,
    sortBy: String,
    onMediaTypeChange: (TmdbCollectionMediaType) -> Unit,
    onSortChange: (String) -> Unit,
    lockTv: Boolean = false,
    lockMovie: Boolean = false
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TmdbChoiceButton(
                label = stringResource(R.string.type_movie),
                selected = mediaType == TmdbCollectionMediaType.MOVIE,
                enabled = !lockTv,
                onClick = { onMediaTypeChange(TmdbCollectionMediaType.MOVIE) }
            )
            TmdbChoiceButton(
                label = stringResource(R.string.type_series),
                selected = mediaType == TmdbCollectionMediaType.TV,
                enabled = !lockMovie,
                onClick = { onMediaTypeChange(TmdbCollectionMediaType.TV) }
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val sorts = listOf(
                TmdbCollectionSort.POPULAR_DESC.value to stringResource(R.string.tmdb_entity_rail_popular),
                TmdbCollectionSort.VOTE_AVERAGE_DESC.value to stringResource(R.string.tmdb_entity_rail_top_rated),
                if (mediaType == TmdbCollectionMediaType.TV) {
                    TmdbCollectionSort.FIRST_AIR_DATE_DESC.value to stringResource(R.string.tmdb_entity_rail_recent)
                } else {
                    TmdbCollectionSort.RELEASE_DATE_DESC.value to stringResource(R.string.tmdb_entity_rail_recent)
                }
            )
            sorts.forEach { (value, label) ->
                TmdbChoiceButton(
                    label = label,
                    selected = sortBy == value,
                    onClick = { onSortChange(value) }
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TmdbChoiceButton(
    label: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.colors(
            containerColor = if (selected) NuvioColors.Secondary.copy(alpha = 0.3f) else NuvioColors.BackgroundCard,
            contentColor = if (selected) NuvioColors.Secondary else NuvioColors.TextSecondary,
            focusedContainerColor = NuvioColors.FocusBackground,
            focusedContentColor = NuvioColors.Primary
        ),
        shape = ButtonDefaults.shape(RoundedCornerShape(12.dp))
    ) {
        Text(label)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TmdbPickerCard(title: String, subtitle: String, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.colors(
            containerColor = NuvioColors.BackgroundCard,
            focusedContainerColor = NuvioColors.FocusBackground
        ),
        border = CardDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, NuvioColors.FocusRing),
                shape = RoundedCornerShape(12.dp)
            )
        ),
        shape = CardDefaults.shape(RoundedCornerShape(12.dp))
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = NuvioColors.TextPrimary)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = NuvioColors.TextTertiary)
        }
    }
}

private fun tmdbModeLabel(mode: TmdbBuilderMode): String {
    return when (mode) {
        TmdbBuilderMode.PRESETS -> "Presets"
        TmdbBuilderMode.LIST -> "Public List"
        TmdbBuilderMode.PRODUCTION -> "Production"
        TmdbBuilderMode.NETWORK -> "Network"
        TmdbBuilderMode.COLLECTION -> "Collection"
        TmdbBuilderMode.DISCOVER -> "Custom"
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun GenrePickerContent(
    title: String,
    selectedGenre: String?,
    genreOptions: List<String>,
    allowAll: Boolean,
    onSelect: (String?) -> Unit,
    onBack: () -> Unit
) {
    val firstOptionFocusRequester = remember { FocusRequester() }

    LaunchedEffect(title, selectedGenre, genreOptions) {
        repeat(5) { androidx.compose.runtime.withFrameNanos { } }
        try { firstOptionFocusRequester.requestFocus() } catch (_: Exception) {}
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 48.dp, start = 48.dp, end = 48.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.collections_editor_genre_filter),
                    style = MaterialTheme.typography.headlineMedium,
                    color = NuvioColors.TextPrimary
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioColors.TextSecondary
                )
            }
            NuvioButton(onClick = onBack) { Text(stringResource(R.string.collections_editor_back)) }
        }

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            var optionIndex = 0
            if (allowAll) {
                item(key = "genre_all") {
                    GenrePickerOptionCard(
                        title = stringResource(R.string.collections_editor_all_genres),
                        selected = selectedGenre == null,
                        onClick = { onSelect(null) },
                        modifier = Modifier.focusRequester(firstOptionFocusRequester)
                    )
                }
                optionIndex += 1
            }

            itemsIndexed(
                items = genreOptions,
                key = { _, genre -> genre }
            ) { index, genre ->
                val useFirstRequester = optionIndex == 0 && index == 0
                GenrePickerOptionCard(
                    title = genre,
                    selected = selectedGenre == genre,
                    onClick = { onSelect(genre) },
                    modifier = if (useFirstRequester) Modifier.focusRequester(firstOptionFocusRequester) else Modifier
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun GenrePickerOptionCard(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.colors(
            containerColor = if (selected) NuvioColors.Secondary.copy(alpha = 0.15f) else NuvioColors.BackgroundCard,
            focusedContainerColor = NuvioColors.FocusBackground
        ),
        border = CardDefaults.border(
            border = if (selected) Border(
                border = BorderStroke(1.dp, NuvioColors.Secondary.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(12.dp)
            ) else Border.None,
            focusedBorder = Border(
                border = BorderStroke(2.dp, NuvioColors.FocusRing),
                shape = RoundedCornerShape(12.dp)
            )
        ),
        shape = CardDefaults.shape(RoundedCornerShape(12.dp)),
        scale = CardDefaults.scale(focusedScale = 1.01f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = NuvioColors.TextPrimary
            )
            if (selected) {
                Text(
                    text = stringResource(R.string.cd_selected),
                    style = MaterialTheme.typography.labelSmall,
                    color = NuvioColors.Secondary
                )
            }
        }
    }
}

private val emojiCategories = linkedMapOf(
    "Streaming" to listOf("🎬", "🎭", "🎥", "📺", "🍿", "🎞️", "📽️", "🎦", "📡", "📻"),
    "Genres" to listOf("💀", "👻", "🔪", "💣", "🚀", "🛸", "🧙", "🦸", "🧟", "🤖", "💘", "😂", "😱", "🤯", "🥺", "😈"),
    "Sports" to listOf("⚽", "🏀", "🏈", "⚾", "🎾", "🏐", "🏒", "🥊", "🏎️", "🏆", "🎯", "🏋️"),
    "Music" to listOf("🎵", "🎶", "🎤", "🎸", "🥁", "🎹", "🎷", "🎺", "🎻", "🪗"),
    "Nature" to listOf("🌍", "🌊", "🏔️", "🌋", "🌅", "🌙", "⭐", "🔥", "❄️", "🌈", "🌸", "🍀"),
    "Animals" to listOf("🐕", "🐈", "🦁", "🐻", "🦊", "🐺", "🦅", "🐉", "🦋", "🐬", "🦈", "🐙"),
    "Food" to listOf("🍕", "🍔", "🍣", "🍜", "🍩", "🍰", "🍷", "🍺", "☕", "🧁", "🌮", "🥗"),
    "Travel" to listOf("✈️", "🚂", "🚗", "⛵", "🏖️", "🗼", "🏰", "🗽", "🎡", "🏕️", "🌆", "🛣️"),
    "People" to listOf("👨‍👩‍👧‍👦", "👫", "👶", "🧒", "👩", "👨", "🧓", "💃", "🕺", "🥷", "🧑‍🚀", "🧑‍🎨"),
    "Objects" to listOf("📱", "💻", "🎮", "🕹️", "📷", "🔮", "💡", "🔑", "💎", "🎁", "📚", "✏️"),
    "Flags" to listOf(
        "🏳️‍🌈", "🏴‍☠️",
        "🇦🇫", "🇦🇱", "🇩🇿", "🇦🇸", "🇦🇩", "🇦🇴", "🇦🇮", "🇦🇬", "🇦🇷", "🇦🇲", "🇦🇼", "🇦🇺",
        "🇦🇹", "🇦🇿", "🇧🇸", "🇧🇭", "🇧🇩", "🇧🇧", "🇧🇾", "🇧🇪", "🇧🇿", "🇧🇯", "🇧🇲", "🇧🇹",
        "🇧🇴", "🇧🇦", "🇧🇼", "🇧🇷", "🇧🇳", "🇧🇬", "🇧🇫", "🇧🇮", "🇰🇭", "🇨🇲", "🇨🇦", "🇨🇻",
        "🇨🇫", "🇹🇩", "🇨🇱", "🇨🇳", "🇨🇴", "🇰🇲", "🇨🇬", "🇨🇩", "🇨🇷", "🇨🇮", "🇭🇷", "🇨🇺",
        "🇨🇼", "🇨🇾", "🇨🇿", "🇩🇰", "🇩🇯", "🇩🇲", "🇩🇴", "🇪🇨", "🇪🇬", "🇸🇻", "🇬🇶", "🇪🇷",
        "🇪🇪", "🇸🇿", "🇪🇹", "🇫🇯", "🇫🇮", "🇫🇷", "🇬🇦", "🇬🇲", "🇬🇪", "🇩🇪", "🇬🇭", "🇬🇷",
        "🇬🇩", "🇬🇹", "🇬🇳", "🇬🇼", "🇬🇾", "🇭🇹", "🇭🇳", "🇭🇰", "🇭🇺", "🇮🇸", "🇮🇳", "🇮🇩",
        "🇮🇷", "🇮🇶", "🇮🇪", "🇮🇱", "🇮🇹", "🇯🇲", "🇯🇵", "🇯🇴", "🇰🇿", "🇰🇪", "🇰🇮", "🇰🇼",
        "🇰🇬", "🇱🇦", "🇱🇻", "🇱🇧", "🇱🇸", "🇱🇷", "🇱🇾", "🇱🇮", "🇱🇹", "🇱🇺", "🇲🇴", "🇲🇬",
        "🇲🇼", "🇲🇾", "🇲🇻", "🇲🇱", "🇲🇹", "🇲🇷", "🇲🇺", "🇲🇽", "🇫🇲", "🇲🇩", "🇲🇨", "🇲🇳",
        "🇲🇪", "🇲🇦", "🇲🇿", "🇲🇲", "🇳🇦", "🇳🇷", "🇳🇵", "🇳🇱", "🇳🇿", "🇳🇮", "🇳🇪", "🇳🇬",
        "🇰🇵", "🇲🇰", "🇳🇴", "🇴🇲", "🇵🇰", "🇵🇼", "🇵🇸", "🇵🇦", "🇵🇬", "🇵🇾", "🇵🇪", "🇵🇭",
        "🇵🇱", "🇵🇹", "🇵🇷", "🇶🇦", "🇷🇴", "🇷🇺", "🇷🇼", "🇰🇳", "🇱🇨", "🇻🇨", "🇼🇸", "🇸🇲",
        "🇸🇹", "🇸🇦", "🇸🇳", "🇷🇸", "🇸🇨", "🇸🇱", "🇸🇬", "🇸🇰", "🇸🇮", "🇸🇧", "🇸🇴", "🇿🇦",
        "🇰🇷", "🇸🇸", "🇪🇸", "🇱🇰", "🇸🇩", "🇸🇷", "🇸🇪", "🇨🇭", "🇸🇾", "🇹🇼", "🇹🇯", "🇹🇿",
        "🇹🇭", "🇹🇱", "🇹🇬", "🇹🇴", "🇹🇹", "🇹🇳", "🇹🇷", "🇹🇲", "🇹🇻", "🇺🇬", "🇺🇦", "🇦🇪",
        "🇬🇧", "🇺🇸", "🇺🇾", "🇺🇿", "🇻🇺", "🇻🇪", "🇻🇳", "🇾🇪", "🇿🇲", "🇿🇼"
    ),
    "Symbols" to listOf("❤️", "💜", "💙", "💚", "💛", "🧡", "🖤", "🤍", "✅", "❌", "⚡", "💯")
)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun EmojiPickerContent(
    selectedEmoji: String?,
    onSelect: (String) -> Unit,
    onBack: () -> Unit
) {
    val firstEmojiFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        repeat(5) { androidx.compose.runtime.withFrameNanos { } }
        try { firstEmojiFocusRequester.requestFocus() } catch (_: Exception) {}
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 48.dp, start = 48.dp, end = 48.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.collections_editor_choose_emoji),
                style = MaterialTheme.typography.headlineMedium,
                color = NuvioColors.TextPrimary
            )
            NuvioButton(onClick = onBack) { Text(stringResource(R.string.collections_editor_back)) }
        }

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            val firstCategory = emojiCategories.keys.first()
            emojiCategories.forEach { (category, emojis) ->
                item(key = "category_$category") {
                    Text(
                        text = category,
                        style = MaterialTheme.typography.titleSmall,
                        color = NuvioColors.TextSecondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp)
                    ) {
                        items(
                            count = emojis.size,
                            key = { "${category}_${emojis[it]}" }
                        ) { index ->
                            val emoji = emojis[index]
                            val isSelected = emoji == selectedEmoji
                            val isFirstEmoji = category == firstCategory && index == 0
                            Card(
                                onClick = { onSelect(emoji) },
                                modifier = (if (isFirstEmoji) Modifier.focusRequester(firstEmojiFocusRequester) else Modifier)
                                    .width(56.dp)
                                    .height(56.dp),
                                colors = CardDefaults.colors(
                                    containerColor = if (isSelected) NuvioColors.Secondary.copy(alpha = 0.3f) else NuvioColors.BackgroundCard,
                                    focusedContainerColor = NuvioColors.FocusBackground
                                ),
                                border = CardDefaults.border(
                                    border = if (isSelected) Border(
                                        border = BorderStroke(2.dp, NuvioColors.Secondary),
                                        shape = RoundedCornerShape(12.dp)
                                    ) else Border.None,
                                    focusedBorder = Border(
                                        border = BorderStroke(2.dp, NuvioColors.FocusRing),
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                ),
                                shape = CardDefaults.shape(RoundedCornerShape(12.dp)),
                                scale = CardDefaults.scale(focusedScale = 1.1f)
                            ) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = emoji,
                                        fontSize = 28.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
