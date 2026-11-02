package com.tutorapp.fluenttutor

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.tutorapp.fluenttutor.ui.screens.ConversationScreen
import com.tutorapp.fluenttutor.ui.screens.ScenarioSelectScreen
import com.tutorapp.fluenttutor.ui.screens.SettingsScreen
import com.tutorapp.fluenttutor.ui.theme.DarkBackground
import com.tutorapp.fluenttutor.ui.theme.FluentTutorTheme
import com.tutorapp.fluenttutor.ui.viewmodel.TutorViewModel

enum class AppScreen {
    SCENARIO_SELECT,
    CONVERSATION,
    SETTINGS
}

class MainActivity : ComponentActivity() {

    private val viewModel: TutorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            FluentTutorTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = DarkBackground
                ) {
                    var currentScreen by remember { mutableStateOf(AppScreen.SCENARIO_SELECT) }
                    val uiState by viewModel.uiState.collectAsState()

                    // 動態麥克風權限請求
                    val permissionLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.RequestPermission()
                    ) { isGranted ->
                        if (!isGranted) {
                            Toast.makeText(
                                this,
                                "請允許錄音權限以進行即時語音對答練習",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }

                    LaunchedEffect(Unit) {
                        val hasPermission = ContextCompat.checkSelfPermission(
                            this@MainActivity,
                            Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED

                        if (!hasPermission) {
                            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }

                    when (currentScreen) {
                        AppScreen.SCENARIO_SELECT -> {
                            ScenarioSelectScreen(
                                scenarios = uiState.availableScenarios,
                                selectedLanguage = uiState.selectedLanguage,
                                onSelectLanguage = { lang ->
                                    viewModel.selectLanguage(lang)
                                },
                                onSelectScenario = { scenario ->
                                    viewModel.initScenario(scenario)
                                    currentScreen = AppScreen.CONVERSATION
                                },
                                onOpenSettings = {
                                    currentScreen = AppScreen.SETTINGS
                                }
                            )
                        }

                        AppScreen.CONVERSATION -> {
                            ConversationScreen(
                                uiState = uiState,
                                onBack = {
                                    currentScreen = AppScreen.SCENARIO_SELECT
                                },
                                onToggleRecording = {
                                    viewModel.toggleRecording()
                                },
                                onToggleAutoVAD = { enabled ->
                                    viewModel.setAutoVAD(enabled)
                                },
                                onSendManualText = { text ->
                                    viewModel.sendManualText(text)
                                },
                                onPlayAudio = { message ->
                                    viewModel.playMessageAudio(message)
                                },
                                onRetryScenario = {
                                    viewModel.retryCurrentScenario()
                                },
                                onDismissDialog = {
                                    viewModel.dismissResultDialog()
                                }
                            )
                        }

                        AppScreen.SETTINGS -> {
                            SettingsScreen(
                                currentServerUrl = uiState.lfmServerUrl,
                                currentMode = uiState.inferenceMode,
                                onSaveConfig = { url, apiKey, mode ->
                                    viewModel.updateServerConfig(url, apiKey, mode)
                                },
                                onBack = {
                                    currentScreen = AppScreen.SCENARIO_SELECT
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
