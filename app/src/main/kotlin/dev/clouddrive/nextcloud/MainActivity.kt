package dev.clouddrive.nextcloud

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import dev.clouddrive.core.model.ThemeMode
import dev.clouddrive.feature.drive.DriveShell

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            val dark = when (state.themeMode) {
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }
            DisposableEffect(dark) {
                val statusBarStyle = if (dark) {
                    SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                }
                enableEdgeToEdge(
                    statusBarStyle = statusBarStyle,
                    navigationBarStyle = statusBarStyle,
                )
                onDispose {}
            }
            CloudDriveTheme(dark) {
                LaunchedEffect(viewModel) {
                    viewModel.events.collect { event ->
                        when (event) {
                            is AppEvent.OpenBrowser -> openLoginBrowser(event.url)
                        }
                    }
                }
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    // A missing account only means signed out after the initial read completes.
                    when {
                        state.initializing -> Unit
                        state.account == null -> LoginScreen(state.loggingIn, state.loginPending, state.error, viewModel::login, viewModel::continueLogin, viewModel::cancelLogin)
                        else -> DriveShell(Modifier.fillMaxSize())
                    }
                }
            }
        }
    }

    private fun openLoginBrowser(url: String) {
        // Keep every step in the default browser's cookie session. A generic
        // ACTION_VIEW can hand Nextcloud links to another installed cloud app.
        val browser = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER)
            .resolveActivity(packageManager)?.packageName?.takeUnless { it == "android" }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
        if (browser != null) intent.setPackage(browser)
        try {
            startActivity(intent)
        } catch (_: android.content.ActivityNotFoundException) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
        }
    }
}

@Composable
private fun LoginScreen(loggingIn: Boolean, loginPending: Boolean, error: String?, onConnect: (String) -> Unit, onContinue: () -> Unit, onCancel: () -> Unit) {
    var server by rememberSaveable { mutableStateOf("") }
    Box(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Image(rememberVectorPainter(Icons.Default.Cloud), null, Modifier.size(88.dp))
            Text("CloudDrive", style = MaterialTheme.typography.headlineLarge)
            Text(
                "Your Nextcloud as a virtual drive. Nothing on this phone is uploaded unless you choose it.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp, bottom = 32.dp),
            )
            OutlinedTextField(
                value = server,
                onValueChange = { server = it },
                label = { Text("Nextcloud server") },
                placeholder = { Text("https://cloud.example.com") },
                enabled = !loggingIn,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            if (error != null) Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp))
            Button(
                onClick = { onConnect(server) },
                enabled = server.isNotBlank() && !loggingIn,
                modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
            ) {
                if (loggingIn) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else {
                    Icon(Icons.Default.CloudQueue, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Connect to cloud")
                }
            }
            if (loggingIn) Text("Finish signing in in your browser. This can take up to 20 minutes.", textAlign = TextAlign.Center, modifier = Modifier.padding(top = 16.dp))
            if (loginPending) {
                OutlinedButton(onClick = onContinue, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    Text("Continue sign-in in browser")
                }
            }
            if (loggingIn) TextButton(onClick = onCancel) { Text("Cancel sign-in") }
        }
    }
}

@Composable
private fun CloudDriveTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colors = when {
        android.os.Build.VERSION.SDK_INT >= 31 && darkTheme -> dynamicDarkColorScheme(context)
        android.os.Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(context)
        darkTheme -> darkColorScheme(primary = androidx.compose.ui.graphics.Color(0xFF8FC9FF), secondary = androidx.compose.ui.graphics.Color(0xFFBBC7DB))
        else -> lightColorScheme(primary = androidx.compose.ui.graphics.Color(0xFF00639B), secondary = androidx.compose.ui.graphics.Color(0xFF51606F))
    }
    MaterialTheme(colorScheme = colors, typography = Typography(), content = content)
}
