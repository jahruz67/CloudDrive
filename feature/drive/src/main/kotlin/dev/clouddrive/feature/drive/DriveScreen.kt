@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.clouddrive.feature.drive

import android.content.Intent
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import dev.clouddrive.core.model.*
import java.text.DateFormat
import kotlin.math.ln
import kotlin.math.pow

enum class MainDestination(val label: String) { FILES("Files"), OFFLINE("Offline"), TRANSFERS("Transfers"), SETTINGS("Settings") }

@Composable
fun DriveShell(modifier: Modifier = Modifier) {
    var destination by rememberSaveable { mutableStateOf(MainDestination.FILES) }
    Scaffold(
        modifier = modifier,
        bottomBar = {
            NavigationBar {
                MainDestination.entries.forEach { item ->
                    NavigationBarItem(
                        selected = destination == item,
                        onClick = { destination = item },
                        icon = { Icon(destinationIcon(item), null) },
                        label = { Text(item.label) },
                    )
                }
            }
        },
    ) { padding ->
        when (destination) {
            MainDestination.FILES -> FilesScreen(Modifier.padding(padding))
            MainDestination.OFFLINE -> OfflineScreen(Modifier.padding(padding))
            MainDestination.TRANSFERS -> TransfersScreen(Modifier.padding(padding))
            MainDestination.SETTINGS -> SettingsScreen(Modifier.padding(padding))
        }
    }
}

private fun destinationIcon(destination: MainDestination) = when (destination) {
    MainDestination.FILES -> Icons.Default.Cloud
    MainDestination.OFFLINE -> Icons.Default.OfflinePin
    MainDestination.TRANSFERS -> Icons.Default.Sync
    MainDestination.SETTINGS -> Icons.Default.Settings
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilesScreen(modifier: Modifier, viewModel: DriveViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var dialog by remember { mutableStateOf<FileDialog?>(null) }
    val uploadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { uri -> runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        viewModel.upload(uris.map { it.toString() })
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is DriveEvent.Message -> Toast.makeText(context, event.text, Toast.LENGTH_SHORT).show()
                is DriveEvent.OpenFile -> {
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", event.file)
                    context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, event.mimeType).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                }
                is DriveEvent.ShareFile -> {
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", event.file)
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(event.mimeType).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share file"))
                }
            }
        }
    }

    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(if (state.path == "/") "My Cloud" else state.path.substringAfterLast('/')) },
            navigationIcon = {
                if (state.path != "/") IconButton(onClick = { viewModel.navigateUp() }) { Icon(Icons.Default.ArrowBack, "Up") }
            },
            actions = {
                IconButton(onClick = { viewModel.setViewMode(if (state.settings.fileViewMode == FileViewMode.LIST) FileViewMode.GRID else FileViewMode.LIST) }) {
                    Icon(if (state.settings.fileViewMode == FileViewMode.LIST) Icons.Default.GridView else Icons.Default.ViewList, "Change layout")
                }
                SortMenu(state.sortField, viewModel::setSort)
            },
        )
        OutlinedTextField(
            value = state.query,
            onValueChange = viewModel::setQuery,
            placeholder = { Text("Search this cloud") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = {
                if (state.query.isNotEmpty()) IconButton(onClick = { viewModel.setQuery("") }) { Icon(Icons.Default.Close, "Clear") }
                else IconButton(onClick = viewModel::search) { Icon(Icons.Default.Search, "Search") }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FileFilter.entries.forEach { filter ->
                FilterChip(selected = state.filter == filter, onClick = { viewModel.setFilter(filter) }, label = { Text(filter.name.lowercase().replaceFirstChar(Char::uppercase)) })
            }
        }
        if (state.path != "/") Text(state.path, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        PullToRefreshBox(isRefreshing = state.loading, onRefresh = viewModel::refresh, modifier = Modifier.weight(1f)) {
            if (state.nodes.isEmpty() && !state.loading) EmptyFiles(state.error)
            else if (state.settings.fileViewMode == FileViewMode.LIST) {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp)) {
                    items(state.nodes, key = RemoteNode::documentId) { node ->
                        FileRow(node, node.documentId in state.selectedIds, { viewModel.navigate(node) }, { viewModel.toggleSelection(node) }) { action -> handleNodeAction(action, node, viewModel) { dialog = it } }
                    }
                }
            } else {
                LazyVerticalGrid(GridCells.Adaptive(144.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(8.dp, 8.dp, 8.dp, 88.dp)) {
                    items(state.nodes, key = RemoteNode::documentId) { node ->
                        FileCard(node, node.documentId in state.selectedIds, { viewModel.navigate(node) }, { viewModel.toggleSelection(node) }) { action -> handleNodeAction(action, node, viewModel) { dialog = it } }
                    }
                }
            }
            Column(Modifier.align(Alignment.BottomEnd).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.End) {
                SmallFloatingActionButton(onClick = { uploadLauncher.launch(arrayOf("*/*")) }) { Icon(Icons.Default.Upload, "Upload") }
                ExtendedFloatingActionButton(onClick = { dialog = FileDialog.CreateFolder }, icon = { Icon(Icons.Default.CreateNewFolder, null) }, text = { Text("New folder") })
            }
        }
    }

    dialog?.let { active ->
        TextInputDialog(active.title, active.initialValue, active.hint, onDismiss = { dialog = null }) { value ->
            when (active) {
                FileDialog.CreateFolder -> viewModel.createFolder(value)
                is FileDialog.Rename -> viewModel.rename(active.node, value)
                is FileDialog.Move -> viewModel.move(active.node, value)
                is FileDialog.Copy -> viewModel.copy(active.node, value)
            }
            dialog = null
        }
    }
}

private enum class NodeAction { FAVORITE, OFFLINE, SHARE, RENAME, MOVE, COPY, DELETE }
private sealed class FileDialog(val title: String, val initialValue: String, val hint: String) {
    data object CreateFolder : FileDialog("Create folder", "", "Folder name")
    class Rename(val node: RemoteNode) : FileDialog("Rename", node.name, "Name")
    class Move(val node: RemoteNode) : FileDialog("Move", node.parentPath, "Destination path")
    class Copy(val node: RemoteNode) : FileDialog("Copy", node.parentPath, "Destination path")
}

private fun handleNodeAction(action: NodeAction, node: RemoteNode, viewModel: DriveViewModel, showDialog: (FileDialog) -> Unit) {
    when (action) {
        NodeAction.FAVORITE -> viewModel.favorite(node)
        NodeAction.OFFLINE -> if (node.cacheState == CacheState.OFFLINE) viewModel.unpin(node) else viewModel.pin(node)
        NodeAction.SHARE -> viewModel.share(node)
        NodeAction.RENAME -> showDialog(FileDialog.Rename(node))
        NodeAction.MOVE -> showDialog(FileDialog.Move(node))
        NodeAction.COPY -> showDialog(FileDialog.Copy(node))
        NodeAction.DELETE -> viewModel.delete(node)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(node: RemoteNode, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit, onAction: (NodeAction) -> Unit) {
    ListItem(
        headlineContent = { Text(node.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(fileSubtitle(node)) },
        leadingContent = { FileVisual(node, Modifier.size(44.dp)) },
        trailingContent = { NodeMenu(node, onAction) },
        modifier = Modifier.fillMaxWidth().background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent).combinedClickable(onClick = onClick, onLongClick = onLongClick),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileCard(node: RemoteNode, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit, onAction: (NodeAction) -> Unit) {
    Card(
        modifier = Modifier.padding(6.dp).fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1.25f).padding(12.dp)) {
            FileVisual(node, Modifier.fillMaxSize().align(Alignment.Center))
            Box(Modifier.align(Alignment.TopEnd)) { NodeMenu(node, onAction) }
        }
        Text(node.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 12.dp).heightIn(min = 40.dp))
        Text(fileSubtitle(node), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(12.dp, 4.dp, 12.dp, 12.dp))
    }
}

@Composable
private fun FileVisual(node: RemoteNode, modifier: Modifier) {
    val context = LocalContext.current
    if (!node.isDirectory && node.mimeType.startsWith("image/") && node.hasPreview) {
        val uri = DocumentsContract.buildDocumentUri("${context.packageName}.documents", node.documentId)
        AsyncImage(model = uri, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier.clip(RoundedCornerShape(8.dp)))
    } else {
        Box(modifier, contentAlignment = Alignment.Center) {
            Icon(if (node.isDirectory) Icons.Default.Folder else Icons.Default.InsertDriveFile, null, tint = if (node.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxSize(.65f))
            when (node.cacheState) {
                CacheState.OFFLINE -> Icon(Icons.Default.CheckCircle, "Offline", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.align(Alignment.BottomEnd).size(18.dp))
                CacheState.CACHED -> Icon(Icons.Default.Schedule, "Cached", tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.align(Alignment.BottomEnd).size(18.dp))
                CacheState.CLOUD_ONLY -> Unit
            }
        }
    }
}

@Composable
private fun NodeMenu(node: RemoteNode, onAction: (NodeAction) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) { Icon(Icons.Default.MoreVert, "File actions") }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem({ Text(if (node.isFavorite) "Remove favorite" else "Favorite") }, { expanded = false; onAction(NodeAction.FAVORITE) }, leadingIcon = { Icon(Icons.Default.Star, null) })
            if (!node.isDirectory) DropdownMenuItem({ Text(if (node.cacheState == CacheState.OFFLINE) "Remove offline copy" else "Available offline") }, { expanded = false; onAction(NodeAction.OFFLINE) }, leadingIcon = { Icon(Icons.Default.OfflinePin, null) })
            if (!node.isDirectory) DropdownMenuItem({ Text("Share") }, { expanded = false; onAction(NodeAction.SHARE) }, leadingIcon = { Icon(Icons.Default.Share, null) })
            if (node.canRename) DropdownMenuItem({ Text("Rename") }, { expanded = false; onAction(NodeAction.RENAME) })
            if (node.canWrite) {
                DropdownMenuItem({ Text("Move") }, { expanded = false; onAction(NodeAction.MOVE) })
                DropdownMenuItem({ Text("Copy") }, { expanded = false; onAction(NodeAction.COPY) })
            }
            if (node.canDelete) DropdownMenuItem({ Text("Delete") }, { expanded = false; onAction(NodeAction.DELETE) }, leadingIcon = { Icon(Icons.Default.Delete, null) })
        }
    }
}

@Composable private fun EmptyFiles(error: String?) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Default.CloudQueue, null, Modifier.size(64.dp)); Text(error ?: "This folder is empty", Modifier.padding(16.dp)) } } }

@Composable
private fun SortMenu(current: SortField, onSort: (SortField) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) { Icon(Icons.Default.Sort, "Sort") }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            SortField.entries.forEach { field -> DropdownMenuItem({ Text(field.name.lowercase().replaceFirstChar(Char::uppercase)) }, { expanded = false; onSort(field) }, trailingIcon = { if (field == current) Icon(Icons.Default.Check, null) }) }
        }
    }
}

@Composable
private fun TextInputDialog(title: String, initial: String, hint: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) },
        text = { OutlinedTextField(value, { value = it }, label = { Text(hint) }, singleLine = true) },
        confirmButton = { TextButton(onClick = { if (value.isNotBlank()) onConfirm(value) }, enabled = value.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun OfflineScreen(modifier: Modifier, viewModel: OfflineViewModel = hiltViewModel()) {
    val nodes by viewModel.nodes.collectAsStateWithLifecycle()
    Column(modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Offline") })
        if (nodes.isEmpty()) EmptyFiles("Files you make available offline appear here")
        else LazyColumn { items(nodes, key = RemoteNode::documentId) { node -> FileRow(node, false, {}, {}) { if (it == NodeAction.OFFLINE) viewModel.remove(node) } } }
    }
}

@Composable
private fun TransfersScreen(modifier: Modifier, viewModel: TransfersViewModel = hiltViewModel()) {
    val transfers by viewModel.transfers.collectAsStateWithLifecycle()
    Column(modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Transfers") })
        if (transfers.isEmpty()) EmptyFiles("No transfers yet")
        else LazyColumn { items(transfers, key = Transfer::id) { transfer ->
            ListItem(
                headlineContent = { Text(transfer.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Column { Text("${transfer.state.name.lowercase().replaceFirstChar(Char::uppercase)} · ${formatBytes(transfer.bytesTransferred)} / ${if (transfer.totalBytes > 0) formatBytes(transfer.totalBytes) else "?"}"); LinearProgressIndicator(progress = { if (transfer.totalBytes > 0) transfer.bytesTransferred.toFloat() / transfer.totalBytes else 0f }, modifier = Modifier.fillMaxWidth()) } },
                leadingContent = { Icon(if (transfer.direction == TransferDirection.UPLOAD) Icons.Default.Upload else Icons.Default.Download, null) },
                trailingContent = { Row { when (transfer.state) {
                    TransferState.RUNNING -> IconButton({ viewModel.pause(transfer.id) }) { Icon(Icons.Default.Pause, "Pause") }
                    TransferState.PAUSED -> IconButton({ viewModel.resume(transfer.id) }) { Icon(Icons.Default.PlayArrow, "Resume") }
                    TransferState.FAILED -> IconButton({ viewModel.retry(transfer.id) }) { Icon(Icons.Default.Refresh, "Retry") }
                    else -> Unit
                }; if (transfer.state in listOf(TransferState.QUEUED, TransferState.RUNNING, TransferState.PAUSED)) IconButton({ viewModel.cancel(transfer.id) }) { Icon(Icons.Default.Close, "Cancel") } } },
            )
        } }
    }
}

@Composable
private fun SettingsScreen(modifier: Modifier, viewModel: SettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val cacheBytes by viewModel.cacheBytes.collectAsStateWithLifecycle()
    Column(modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Settings") })
        LazyColumn {
            item { SettingsHeader("Appearance") }
            item { ChoiceSetting("Theme", settings.themeMode.name.lowercase().replaceFirstChar(Char::uppercase), ThemeMode.entries, { it.name.lowercase().replaceFirstChar(Char::uppercase) }, viewModel::setTheme) }
            item { SettingsHeader("Transfers") }
            item { SwitchSetting("Wi-Fi only", "Queue transfers while using mobile data", settings.wifiOnly, viewModel::setWifiOnly) }
            item { ChoiceSetting("Concurrent transfers", settings.maxConcurrentTransfers.toString(), (1..4).toList(), Int::toString, viewModel::setConcurrent) }
            item { SettingsHeader("Cache") }
            item { ListItem(headlineContent = { Text("Temporary cache") }, supportingContent = { Text("${formatBytes(cacheBytes)} used · 1 GB limit · files older than 7 days may be removed") }, trailingContent = { TextButton(onClick = viewModel::clearCache) { Text("Clear") } }) }
            item { SettingsHeader("Account") }
            item { ListItem(headlineContent = { Text("Remove account") }, supportingContent = { Text("Revoke this app password and remove local metadata") }, trailingContent = { TextButton(onClick = viewModel::logout) { Text("Log out") } }) }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

@Composable private fun SettingsHeader(text: String) { Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(16.dp, 20.dp, 16.dp, 4.dp)) }
@Composable private fun SwitchSetting(title: String, subtitle: String, checked: Boolean, onChecked: (Boolean) -> Unit) { ListItem(headlineContent = { Text(title) }, supportingContent = { Text(subtitle) }, trailingContent = { Switch(checked, onChecked) }) }

@Composable
private fun <T> ChoiceSetting(title: String, current: String, choices: List<T>, label: (T) -> String, onChoose: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box { ListItem(headlineContent = { Text(title) }, supportingContent = { Text(current) }, modifier = Modifier.fillMaxWidth(), trailingContent = { IconButton({ expanded = true }) { Icon(Icons.Default.ExpandMore, "Choose") } }); DropdownMenu(expanded, { expanded = false }) { choices.forEach { value -> DropdownMenuItem({ Text(label(value)) }, { expanded = false; onChoose(value) }) } } }
}

private fun fileSubtitle(node: RemoteNode): String = buildString {
    append(if (node.isDirectory) "Folder" else formatBytes(node.size))
    if (node.modifiedAt.toEpochMilli() > 0) append(" · ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(java.util.Date.from(node.modifiedAt))}")
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val unit = (ln(bytes.toDouble()) / ln(1024.0)).toInt().coerceAtMost(5)
    return "%.1f %sB".format(bytes / 1024.0.pow(unit), "KMGTPE"[unit - 1])
}
