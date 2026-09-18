package dev.clouddrive.feature.drive

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.clouddrive.core.data.settings.AppSettings
import dev.clouddrive.core.data.settings.SettingsRepository
import dev.clouddrive.core.model.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

enum class FileFilter { ALL, RECENT, FAVORITES }

data class DriveUiState(
    val path: String = "/",
    val nodes: List<RemoteNode> = emptyList(),
    val filter: FileFilter = FileFilter.ALL,
    val query: String = "",
    val sortField: SortField = SortField.NAME,
    val sortDirection: SortDirection = SortDirection.ASCENDING,
    val selectedIds: Set<String> = emptySet(),
    val loading: Boolean = false,
    val error: String? = null,
    val settings: AppSettings = AppSettings(),
)

sealed interface DriveEvent {
    data class OpenFile(val file: File, val mimeType: String) : DriveEvent
    data class ShareFile(val file: File, val mimeType: String) : DriveEvent
    data class Message(val text: String) : DriveEvent
}

@HiltViewModel
class DriveViewModel @Inject constructor(
    private val repository: CloudRepository,
    private val transferManager: TransferManager,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    private val path = MutableStateFlow("/")
    private val filter = MutableStateFlow(FileFilter.ALL)
    private val query = MutableStateFlow("")
    private val sortField = MutableStateFlow(SortField.NAME)
    private val sortDirection = MutableStateFlow(SortDirection.ASCENDING)
    private val selected = MutableStateFlow<Set<String>>(emptySet())
    private val loading = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)
    private val searchResults = MutableStateFlow<List<RemoteNode>?>(null)
    private val eventsChannel = Channel<DriveEvent>(Channel.BUFFERED)
    val events = eventsChannel.receiveAsFlow()

    private val visibleNodes = combine(path, filter) { currentPath, currentFilter -> currentPath to currentFilter }
        .flatMapLatest { (currentPath, currentFilter) ->
            when (currentFilter) {
                FileFilter.ALL -> repository.observeFolder(currentPath)
                FileFilter.RECENT -> repository.observeRecent()
                FileFilter.FAVORITES -> repository.observeFavorites()
            }
        }

    val state: StateFlow<DriveUiState> = combine(
        combine(path, filter, query, sortField, sortDirection) { p, f, q, s, d -> Header(p, f, q, s, d) },
        combine(visibleNodes, searchResults, selected, loading, error) { n, search, sel, load, err -> Body(search ?: n, sel, load, err) },
        settingsRepository.settings,
    ) { header, body, settings ->
        DriveUiState(
            path = header.path, filter = header.filter, query = header.query,
            sortField = header.sort, sortDirection = header.direction,
            nodes = sort(body.nodes, header.sort, header.direction), selectedIds = body.selected,
            loading = body.loading, error = body.error, settings = settings,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DriveUiState())

    init { refresh() }

    fun navigate(node: RemoteNode) {
        if (node.isDirectory) {
            path.value = node.path; filter.value = FileFilter.ALL; selected.value = emptySet(); searchResults.value = null; refresh()
        } else open(node)
    }

    fun navigateUp(): Boolean {
        if (path.value == "/") return false
        path.value = RemotePath.parent(path.value); selected.value = emptySet(); refresh(); return true
    }

    fun setFilter(value: FileFilter) { filter.value = value; selected.value = emptySet(); searchResults.value = null }
    fun setQuery(value: String) { query.value = value; if (value.isBlank()) searchResults.value = null }
    fun setSort(field: SortField) {
        if (sortField.value == field) sortDirection.value = if (sortDirection.value == SortDirection.ASCENDING) SortDirection.DESCENDING else SortDirection.ASCENDING
        else { sortField.value = field; sortDirection.value = SortDirection.ASCENDING }
    }

    fun search() = action {
        if (query.value.isBlank()) searchResults.value = null
        else when (val result = repository.search(query.value, if (filter.value == FileFilter.ALL) path.value else "/")) {
            is CloudResult.Success -> searchResults.value = result.value
            is CloudResult.Failure -> fail(result.error)
        }
    }

    fun refresh() = action {
        loading.value = true
        when (val result = repository.refreshFolder(path.value)) {
            is CloudResult.Failure -> fail(result.error)
            else -> error.value = null
        }
        loading.value = false
    }

    fun toggleSelection(node: RemoteNode) { selected.value = selected.value.toMutableSet().apply { if (!add(node.documentId)) remove(node.documentId) } }
    fun clearSelection() { selected.value = emptySet() }

    fun createFolder(name: String) = actionResult({ repository.createFolder(path.value, name) }, "Folder created")
    fun rename(node: RemoteNode, name: String) = actionResult({ repository.rename(node, name) }, "Renamed")
    fun move(node: RemoteNode, targetPath: String) = actionResult({ repository.move(node, targetPath) }, "Moved")
    fun copy(node: RemoteNode, targetPath: String) = actionResult({ repository.copy(node, targetPath) }, "Copied")
    fun delete(node: RemoteNode) = actionResult({ repository.delete(node) }, "Moved to server trash")
    fun favorite(node: RemoteNode) = actionResult({ repository.setFavorite(node, !node.isFavorite) }, if (node.isFavorite) "Removed from favorites" else "Added to favorites")
    fun pin(node: RemoteNode) = actionResult({ repository.pinOffline(node) }, "Available offline")
    fun unpin(node: RemoteNode) = actionResult({ repository.unpinOffline(node) }, "Offline copy removed")

    fun upload(uris: List<String>) = action {
        uris.forEach { uri ->
            val displayName = uri.substringAfterLast('/').substringBefore('?').ifBlank { "upload" }
            transferManager.enqueueUpload(uri, RemotePath.child(path.value, displayName), displayName)
        }
        eventsChannel.send(DriveEvent.Message("${uris.size} upload${if (uris.size == 1) "" else "s"} queued"))
    }

    fun open(node: RemoteNode) = action {
        when (val result = repository.cachedFile(node)) {
            is CloudResult.Success -> eventsChannel.send(DriveEvent.OpenFile(result.value, node.mimeType))
            is CloudResult.Failure -> fail(result.error)
        }
    }

    fun share(node: RemoteNode) = action {
        when (val result = repository.cachedFile(node)) {
            is CloudResult.Success -> eventsChannel.send(DriveEvent.ShareFile(result.value, node.mimeType))
            is CloudResult.Failure -> fail(result.error)
        }
    }

    fun setViewMode(mode: FileViewMode) = action { settingsRepository.setViewMode(mode) }

    private fun <T> actionResult(block: suspend () -> CloudResult<T>, success: String) = action {
        when (val result = block()) {
            is CloudResult.Success -> { eventsChannel.send(DriveEvent.Message(success)); refresh() }
            is CloudResult.Failure -> fail(result.error)
        }
    }

    private suspend fun fail(problem: Throwable) {
        error.value = problem.message ?: "Operation failed"
        eventsChannel.send(DriveEvent.Message(error.value!!))
    }

    private fun action(block: suspend () -> Unit) { viewModelScope.launch { runCatching { block() }.onFailure { fail(it) } } }

    private fun sort(nodes: List<RemoteNode>, field: SortField, direction: SortDirection): List<RemoteNode> {
        val comparator = when (field) {
            SortField.NAME -> compareBy<RemoteNode> { it.name.lowercase() }
            SortField.MODIFIED -> compareBy { it.modifiedAt }
            SortField.SIZE -> compareBy { it.size }
            SortField.TYPE -> compareBy<RemoteNode> { it.mimeType }.thenBy { it.name.lowercase() }
        }.let { if (direction == SortDirection.ASCENDING) it else it.reversed() }
        return nodes.sortedWith(compareByDescending<RemoteNode> { it.isDirectory }.then(comparator))
    }

    private data class Header(val path: String, val filter: FileFilter, val query: String, val sort: SortField, val direction: SortDirection)
    private data class Body(val nodes: List<RemoteNode>, val selected: Set<String>, val loading: Boolean, val error: String?)
}

@HiltViewModel
class OfflineViewModel @Inject constructor(private val repository: CloudRepository) : ViewModel() {
    val nodes = repository.observeOfflineFiles().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    fun remove(node: RemoteNode) { viewModelScope.launch { repository.unpinOffline(node) } }
}

@HiltViewModel
class TransfersViewModel @Inject constructor(private val manager: TransferManager) : ViewModel() {
    val transfers = manager.observeTransfers().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    fun pause(id: String) { viewModelScope.launch { manager.pause(id) } }
    fun resume(id: String) { viewModelScope.launch { manager.resume(id) } }
    fun cancel(id: String) { viewModelScope.launch { manager.cancel(id) } }
    fun retry(id: String) { viewModelScope.launch { manager.retry(id) } }
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val cacheManager: CacheManager,
    private val repository: CloudRepository,
) : ViewModel() {
    val settings = settingsRepository.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())
    private val cacheBytesMutable = MutableStateFlow(0L)
    val cacheBytes: StateFlow<Long> = cacheBytesMutable
    init { refreshCache() }
    fun setTheme(value: ThemeMode) { viewModelScope.launch { settingsRepository.setTheme(value) } }
    fun setWifiOnly(value: Boolean) { viewModelScope.launch { settingsRepository.setWifiOnly(value) } }
    fun setConcurrent(value: Int) { viewModelScope.launch { settingsRepository.setMaxTransfers(value) } }
    fun clearCache() { viewModelScope.launch { cacheManager.clearDisposable(); refreshCache() } }
    fun logout() { viewModelScope.launch { repository.logout() } }
    private fun refreshCache() { viewModelScope.launch { cacheBytesMutable.value = cacheManager.disposableBytes() } }
}
