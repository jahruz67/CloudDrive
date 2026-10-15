package dev.clouddrive.nextcloud

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.clouddrive.core.data.auth.LoginCoordinator
import dev.clouddrive.core.data.settings.SettingsRepository
import dev.clouddrive.core.model.Account
import dev.clouddrive.core.model.CloudRepository
import dev.clouddrive.core.model.LoginStart
import dev.clouddrive.core.model.ThemeMode
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import javax.inject.Inject

data class AppUiState(
    val account: Account? = null,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val loggingIn: Boolean = false,
    val error: String? = null,
    val initializing: Boolean = true,
    val loginPending: Boolean = false,
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
    private val pendingLogin = MutableStateFlow<LoginStart?>(null)
    private var loginJob: Job? = null
    private val eventChannel = Channel<AppEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()

    val state = combine(repository.observeAccount(), settingsRepository.settings, loggingIn, error, pendingLogin) { account, settings, inProgress, problem, pending ->
        AppUiState(account, settings.themeMode, inProgress, problem, initializing = false, loginPending = pending != null)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppUiState())

    fun login(serverUrl: String) {
        if (loggingIn.value) return
        loginJob = viewModelScope.launch {
            loggingIn.value = true
            error.value = null
            try {
                val start: LoginStart = loginCoordinator.start(serverUrl)
                pendingLogin.value = start
                eventChannel.send(AppEvent.OpenBrowser(start.loginUrl))
                loginCoordinator.await(start)
            } catch (_: TimeoutCancellationException) {
                error.value = "The sign-in link expired. Connect again to get a new link."
            } catch (canceled: CancellationException) {
                throw canceled
            } catch (problem: Exception) {
                error.value = problem.message ?: "Could not connect to Nextcloud"
            } finally {
                pendingLogin.value = null
                loggingIn.value = false
            }
        }
    }

    fun continueLogin() {
        pendingLogin.value?.let { start ->
            viewModelScope.launch { eventChannel.send(AppEvent.OpenBrowser(start.loginUrl)) }
        }
    }

    fun cancelLogin() {
        loginJob?.cancel()
    }
}
