package dev.clouddrive.nextcloud

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.clouddrive.core.data.auth.LoginCoordinator
import dev.clouddrive.core.data.settings.SettingsRepository
import dev.clouddrive.core.model.Account
import dev.clouddrive.core.model.CloudRepository
import dev.clouddrive.core.model.CloudResult
import dev.clouddrive.core.model.LoginStart
import dev.clouddrive.core.model.ThemeMode
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AppUiState(
    val account: Account? = null,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val loggingIn: Boolean = false,
    val error: String? = null,
)

sealed interface AppEvent { data class OpenBrowser(val url: String) : AppEvent }

@HiltViewModel
class AppViewModel @Inject constructor(
    private val repository: CloudRepository,
    private val loginCoordinator: LoginCoordinator,
    settingsRepository: SettingsRepository,
) : ViewModel() {
    private val loggingIn = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)
    private val eventChannel = Channel<AppEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()

    val state = combine(repository.observeAccount(), settingsRepository.settings, loggingIn, error) { account, settings, inProgress, problem ->
        AppUiState(account, settings.themeMode, inProgress, problem)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppUiState())

    fun login(serverUrl: String) {
        if (loggingIn.value) return
        viewModelScope.launch {
            loggingIn.value = true
            error.value = null
            try {
                val start: LoginStart = loginCoordinator.start(serverUrl)
                eventChannel.send(AppEvent.OpenBrowser(start.loginUrl))
                loginCoordinator.await(start)
                when (val refresh = repository.refreshFolder("/")) {
                    is CloudResult.Failure -> error.value = refresh.error.message
                    else -> Unit
                }
            } catch (problem: Exception) {
                error.value = problem.message ?: "Could not connect to Nextcloud"
            } finally {
                loggingIn.value = false
            }
        }
    }
}

