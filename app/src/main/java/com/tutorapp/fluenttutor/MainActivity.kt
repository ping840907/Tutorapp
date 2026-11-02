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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.tutorapp.fluenttutor.ui.screens.ConversationScreen
import com.tutorapp.fluenttutor.ui.screens.ScenarioSelectScreen
import com.tutorapp.fluenttutor.ui.theme.DarkBackground
import com.tutorapp.fluenttutor.ui.theme.FluentTutorTheme
import com.tutorapp.fluenttutor.ui.viewmodel.TutorViewModel

enum class AppScreen {
    SCENARIO_SELECT,
    CONVERSATION
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
                    var currentScreen by rememberSaveable { mutableStateOf(AppScreen.SCENARIO_SELECT) }
                    val uiState by viewModel.uiState.collectAsState()
                    val lifecycleOwner = LocalLifecycleOwner.current

                    // 生命週期監聽：退到背景時停止收音與播放，節省手機電量
                    DisposableEffect(lifecycleOwner) {
                        val observer = LifecycleEventObserver { _, event ->
                            if (event == Lifecycle.Event.ON_STOP) {
                                viewModel.stopAllAudio()
                            }
                        }
                        lifecycleOwner.lifecycle.addObserver(observer)
                        onDispose {
                            lifecycleOwner.lifecycle.removeObserver(observer)
                        }
                    }

                    // 麥克風權限動態申請
                    val permissionLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.RequestPermission()
                    ) { isGranted ->
                        if (!isGranted) {
                            viewModel.onPermissionDenied()
                            Toast.makeText(
                                this,
                                "Microphone permission is required for voice conversation.",
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
                                onSelectScenario = { scenario ->
                                    viewModel.prepareScenario(scenario)
                                    currentScreen = AppScreen.CONVERSATION
                                }
                            )
                        }

                        AppScreen.CONVERSATION -> {
                            ConversationScreen(
                                uiState = uiState,
                                onBack = {
                                    viewModel.stopAllAudio()
                                    currentScreen = AppScreen.SCENARIO_SELECT
                                },
                                onEnterScreen = {
                                    viewModel.playGreetingOnScreenEnter()
                                },
                                onToggleRecording = {
                                    val hasPermission = ContextCompat.checkSelfPermission(
                                        this@MainActivity,
                                        Manifest.permission.RECORD_AUDIO
                                    ) == PackageManager.PERMISSION_GRANTED

                                    if (hasPermission) {
                                        viewModel.toggleRecording()
                                    } else {
                                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    }
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
                                onReplayLastAiMessage = {
                                    viewModel.replayLastAiMessage()
                                },
                                onRetryScenario = {
                                    viewModel.retryCurrentScenario()
                                },
                                onDismissDialog = {
                                    viewModel.dismissResultDialog()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
