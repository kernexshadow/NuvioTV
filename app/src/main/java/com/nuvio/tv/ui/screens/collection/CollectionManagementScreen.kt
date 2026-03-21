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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.nuvio.tv.domain.model.Collection
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.theme.NuvioColors

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun CollectionManagementScreen(
    viewModel: CollectionManagementViewModel = hiltViewModel(),
    onNavigateToEditor: (String?) -> Unit,
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    if (uiState.isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            LoadingIndicator()
        }
        return
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
                text = "Collections",
                style = MaterialTheme.typography.headlineMedium,
                color = NuvioColors.TextPrimary
            )
            val newButtonFocusRequester = remember { FocusRequester() }
            LaunchedEffect(Unit) {
                repeat(3) { withFrameNanos { } }
                try { newButtonFocusRequester.requestFocus() } catch (_: Exception) {}
            }
            Button(
                onClick = { onNavigateToEditor(null) },
                modifier = Modifier.focusRequester(newButtonFocusRequester),
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
                shape = ButtonDefaults.shape(RoundedCornerShape(12.dp))
            ) {
                Icon(imageVector = Icons.Default.Add, contentDescription = "Add Collection")
                Spacer(modifier = Modifier.width(8.dp))
                Text("New Collection")
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        if (uiState.collections.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No collections yet. Create one to organize your catalogs.",
                    color = NuvioColors.TextSecondary,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 48.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                itemsIndexed(
                    items = uiState.collections,
                    key = { _, item -> item.id }
                ) { index, collection ->
                    CollectionListItem(
                        collection = collection,
                        isFirst = index == 0,
                        isLast = index == uiState.collections.size - 1,
                        onEdit = { onNavigateToEditor(collection.id) },
                        onDelete = { viewModel.deleteCollection(collection.id) },
                        onMoveUp = { viewModel.moveUp(index) },
                        onMoveDown = { viewModel.moveDown(index) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CollectionListItem(
    collection: Collection,
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
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = collection.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = NuvioColors.TextPrimary
                )
                Text(
                    text = "${collection.folders.size} folder${if (collection.folders.size != 1) "s" else ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioColors.TextTertiary
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(
                    onClick = onMoveUp,
                    enabled = !isFirst,
                    colors = ButtonDefaults.colors(
                        containerColor = NuvioColors.BackgroundCard,
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
                    Icon(Icons.Default.KeyboardArrowUp, "Move Up")
                }

                Button(
                    onClick = onMoveDown,
                    enabled = !isLast,
                    colors = ButtonDefaults.colors(
                        containerColor = NuvioColors.BackgroundCard,
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
                    Icon(Icons.Default.KeyboardArrowDown, "Move Down")
                }

                Button(
                    onClick = onEdit,
                    colors = ButtonDefaults.colors(
                        containerColor = NuvioColors.BackgroundCard,
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
                    Icon(Icons.Default.Edit, "Edit")
                }

                Button(
                    onClick = onDelete,
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
                    Icon(Icons.Default.Delete, "Delete")
                }
            }
        }
    }
}
