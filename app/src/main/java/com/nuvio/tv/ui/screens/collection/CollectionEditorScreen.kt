package com.nuvio.tv.ui.screens.collection

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.itemsIndexed
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
import com.nuvio.tv.domain.model.CollectionFolder
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.theme.NuvioColors

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
    content: @Composable () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier,
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 48.dp, start = 48.dp, end = 48.dp)
    ) {
        Text(
            text = if (uiState.isNew) "New Collection" else "Edit Collection",
            style = MaterialTheme.typography.headlineMedium,
            color = NuvioColors.TextPrimary
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Row Title",
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
                placeholder = "Collection name"
            )
            NuvioButton(onClick = { viewModel.save { onBack() } }) {
                Text("Save")
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Folders",
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

        LazyColumn(
            contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            itemsIndexed(
                items = uiState.folders,
                key = { _, folder -> folder.id }
            ) { index, folder ->
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

            item(key = "add_folder") {
                Box(modifier = Modifier.padding(vertical = 4.dp)) {
                    NuvioButton(onClick = { viewModel.addFolder() }) {
                        Icon(imageVector = Icons.Default.Add, contentDescription = "Add")
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Add Folder")
                    }
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
                    text = "${folder.tileShape.name.lowercase().replaceFirstChar { it.uppercase() }} - ${folder.catalogSources.size} catalog${if (folder.catalogSources.size != 1) "s" else ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioColors.TextTertiary
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                NuvioButton(onClick = onMoveUp) {
                    Icon(Icons.Default.KeyboardArrowUp, "Move Up", tint = if (!isFirst) NuvioColors.TextSecondary else NuvioColors.TextTertiary)
                }
                NuvioButton(onClick = onMoveDown) {
                    Icon(Icons.Default.KeyboardArrowDown, "Move Down", tint = if (!isLast) NuvioColors.TextSecondary else NuvioColors.TextTertiary)
                }
                NuvioButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, "Edit", tint = NuvioColors.TextSecondary)
                }
                NuvioButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, "Delete", tint = NuvioColors.TextSecondary)
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
            alreadyAdded = folder.catalogSources,
            onToggle = { viewModel.toggleCatalogSource(it) },
            onBack = { viewModel.hideCatalogPicker() }
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
                text = "Edit Folder",
                style = MaterialTheme.typography.headlineMedium,
                color = NuvioColors.TextPrimary
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NuvioButton(onClick = { viewModel.cancelFolderEdit() }) {
                    Text("Cancel")
                }
                NuvioButton(onClick = { viewModel.saveFolderEdit() }) {
                    Text("Save")
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        val catalogFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }
        var pendingFocusIndex by remember { mutableStateOf(-1) }

        LaunchedEffect(pendingFocusIndex, folder.catalogSources.size) {
            if (pendingFocusIndex >= 0) {
                val targetIndex = pendingFocusIndex.coerceAtMost(folder.catalogSources.lastIndex)
                if (targetIndex >= 0) {
                    val targetSource = folder.catalogSources[targetIndex]
                    val targetKey = "${targetSource.addonId}_${targetSource.type}_${targetSource.catalogId}"
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
                Text("Folder Title", style = MaterialTheme.typography.labelLarge, color = NuvioColors.TextSecondary)
                Spacer(modifier = Modifier.height(8.dp))
                NuvioTextField(
                    value = folder.title,
                    onValueChange = { viewModel.updateFolderTitle(it) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = "Folder name",
                    focusRequester = titleFocusRequester
                )
            }

            item {
                Text("Cover Image", style = MaterialTheme.typography.labelLarge, color = NuvioColors.TextSecondary)
                Spacer(modifier = Modifier.height(4.dp))
                Text("Enter a URL to a custom cover image, or leave empty to use a folder icon.", style = MaterialTheme.typography.bodySmall, color = NuvioColors.TextTertiary)
                Spacer(modifier = Modifier.height(8.dp))
                NuvioTextField(
                    value = folder.coverImageUrl ?: "",
                    onValueChange = { viewModel.updateFolderCoverImage(it) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = "https://..."
                )
            }

            item {
                Text("Tile Shape", style = MaterialTheme.typography.labelLarge, color = NuvioColors.TextSecondary)
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
                            PosterShape.POSTER -> "Poster"
                            PosterShape.LANDSCAPE -> "Wide"
                            PosterShape.SQUARE -> "Square"
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
                                text = "Hide Title",
                                style = MaterialTheme.typography.titleMedium,
                                color = NuvioColors.TextPrimary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Only show the cover image",
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
                    Text("Catalogs", style = MaterialTheme.typography.labelLarge, color = NuvioColors.TextSecondary)
                    Text(
                        "${folder.catalogSources.size} catalog${if (folder.catalogSources.size != 1) "s" else ""}",
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioColors.TextTertiary
                    )
                }
            }

            itemsIndexed(
                items = folder.catalogSources,
                key = { _, source -> "${source.addonId}_${source.type}_${source.catalogId}" }
            ) { index, source ->
                val catalog = uiState.availableCatalogs.find {
                    it.addonId == source.addonId && it.type == source.type && it.catalogId == source.catalogId
                }
                val sourceKey = "${source.addonId}_${source.type}_${source.catalogId}"
                val removeFocusRequester = catalogFocusRequesters.getOrPut(sourceKey) { FocusRequester() }
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
                                text = catalog?.catalogName?.replaceFirstChar { it.uppercase() } ?: source.catalogId,
                                style = MaterialTheme.typography.bodyMedium,
                                color = NuvioColors.TextPrimary
                            )
                            Text(
                                text = "${source.type} - ${catalog?.addonName ?: source.addonId}",
                                style = MaterialTheme.typography.bodySmall,
                                color = NuvioColors.TextTertiary
                            )
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
                            Icon(Icons.Default.Close, "Remove")
                        }
                    }
                }
            }

            item {
                NuvioButton(onClick = { viewModel.showCatalogPicker() }) {
                    Icon(Icons.Default.Add, "Add")
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Add Catalog")
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CatalogPickerContent(
    catalogs: List<AvailableCatalog>,
    alreadyAdded: List<com.nuvio.tv.domain.model.CollectionCatalogSource>,
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
                text = "Select Catalogs",
                style = MaterialTheme.typography.headlineMedium,
                color = NuvioColors.TextPrimary
            )
            NuvioButton(onClick = onBack) { Text("Done") }
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
                    it.addonId == catalog.addonId && it.type == catalog.type && it.catalogId == catalog.catalogId
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
                            Text(
                                text = "${catalog.type} - ${catalog.addonName}",
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
                                contentDescription = "Add",
                                tint = NuvioColors.TextTertiary
                            )
                        }
                    }
                }
            }
        }
    }
}
