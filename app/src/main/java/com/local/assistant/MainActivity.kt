package com.local.assistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.local.assistant.ui.AppViewModel
import com.local.assistant.ui.BootState
import com.local.assistant.ui.chat.ChatScreen
import com.local.assistant.ui.onboarding.OnboardingScreen
import com.local.assistant.ui.settings.SettingsScreen
import com.local.assistant.ui.theme.LocalAssistantTheme
import com.local.assistant.ui.theme.Palette

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            LocalAssistantTheme {
                Surface(
                    Modifier.fillMaxSize().background(Palette.White),
                    color = Palette.White,
                ) {
                    AppRoot()
                }
            }
        }
    }
}

@Composable
private fun AppRoot(appViewModel: AppViewModel = viewModel()) {
    val boot by appViewModel.state.collectAsStateWithLifecycle()
    val navController = rememberNavController()
    val app = LocalContext.current.applicationContext as LocalAssistantApp
    val voiceAvailable = app.container.transcriptionSource.isAvailable

    // Everything before Ready is a single gated flow; there is nothing useful to
    // show behind it, so the nav graph only starts once the engine is live.
    if (boot !is BootState.Ready) {
        OnboardingScreen(
            state = boot,
            onDownload = appViewModel::startDownload,
            onPause = appViewModel::pauseDownload,
            onRetry = appViewModel::retry,
        )
        return
    }

    NavHost(navController = navController, startDestination = "chat") {
        composable("chat") {
            ChatScreen(
                onOpenSettings = { navController.navigate("settings") },
                voiceAvailable = voiceAvailable,
            )
        }
        composable("settings") {
            SettingsScreen(
                bootState = boot,
                onBack = { navController.popBackStack() },
                onRecalibrate = appViewModel::recalibrate,
            )
        }
    }
}
