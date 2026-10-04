package com.emilioaugust.copypus.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.emilioaugust.copypus.ClipboardManagerHelper
import com.emilioaugust.copypus.R
import com.emilioaugust.copypus.data.entity.ClipboardItem
import com.emilioaugust.copypus.data.viewmodel.MainViewModel
import com.emilioaugust.copypus.ui.EditSheet
import com.emilioaugust.copypus.utils.formatSectionTitle
import kotlinx.coroutines.launch
import kotlin.collections.component1
import kotlin.collections.component2

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImagesScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val clipboardHelper = remember { ClipboardManagerHelper(context.applicationContext) }
    val clipboardImages by viewModel.images.collectAsState()
    var editingItem by remember { mutableStateOf<ClipboardItem?>(null) }
    val scope = rememberCoroutineScope()

    val groupedImages = clipboardImages
        .filter { it.type == "IMAGE" }
        .groupBy { formatSectionTitle(it.timestamp, context) }

    Scaffold(
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.padding(0.dp)
            ) { data -> CustomSnackBar(data) }
        },
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.images),
                        color = MaterialTheme.colorScheme.tertiary,
                        fontWeight = FontWeight.Bold
                    )
                },
                actions = {
                    ClipboardTopBarMenu(onClearAll = {
                        viewModel.clearAllImages()
                        Toast.makeText(
                            context,
                            context.getString(R.string.it_s_empty_text),
                            Toast.LENGTH_SHORT
                        ).show()
                    })
                },
                windowInsets = WindowInsets(0, 0, 0, 0)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        if (groupedImages.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.empty_history),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.LightGray,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                groupedImages.forEach { (sectionTitle, sectionItems) ->
                    item(key = "section_$sectionTitle") {
                        Text(
                            text = sectionTitle,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.Gray,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }

                    items(
                        items = sectionItems,
                        key = { it.id }
                    ) { item ->
                        ClipboardItemCard(
                            item = item,
                            onCopy = {
                                when (item.type) {
                                    "TEXT" if !item.text.isNullOrBlank() -> {
                                        clipboardHelper.copyTextToClipboard(item.text)

                                        Toast.makeText(
                                            context,
                                            R.string.clipboard_saved_text,
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                    "IMAGE" if !item.imagePath.isNullOrBlank() -> {
                                        clipboardHelper.copyImageToClipboard(item.imagePath)
                                    }
                                }
                            },
                            onFavorite = {
                                viewModel.toggleFavorite(item)
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.added_to_favorites_text),
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                            onDelete = {
                                viewModel.deleteItem(item)
                                scope.launch {
                                    val result = snackbarHostState.showSnackbar(
                                        message = context.getString(R.string.clipboard_deleted_text),
                                        actionLabel = context.getString(R.string.undo_text),
                                        duration = SnackbarDuration.Short
                                    )
                                    if (result == SnackbarResult.ActionPerformed) {
                                        viewModel.restoreItem(item)
                                    }
                                }
                            },
                            onLongClick = {
                                if(item.type == "IMAGE") {
                                    editingItem = item
                                }
                            }
                        )
                    }
                }
            }
        }

        editingItem?.let { item ->
            if (item.type == "IMAGE" && item.text != null) {
                ModalBottomSheet(
                    onDismissRequest = {
                        editingItem = null
                    },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ) {
                    EditSheet(
                        initialText = item.text,
                        onDismiss = {
                            editingItem = null
                        },
                        onSave = { newText ->
                            viewModel.updateItem(
                                item.copy(text = newText)
                            )
                            editingItem = null
                        }
                    )
                }
            }
        }
    }
}