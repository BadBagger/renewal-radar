package com.renewalradar.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.renewalradar.app.ui.RenewalRadarRoot
import com.renewalradar.app.ui.RenewalViewModel
import com.renewalradar.app.ui.theme.RenewalRadarTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as RenewalRadarApp
        setContent {
            val viewModel: RenewalViewModel = viewModel(
                factory = RenewalViewModel.Factory(app.repository, app.settingsStore)
            )
            val state by viewModel.uiState.collectAsState()

            RenewalRadarTheme(darkTheme = state.settings.darkModeEnabled) {
                val context = LocalContext.current
                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) {}

                LaunchedEffect(Unit) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        val granted = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.POST_NOTIFICATIONS
                        ) == PackageManager.PERMISSION_GRANTED
                        if (!granted) permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }

                RenewalRadarRoot(
                    state = state,
                    onSave = viewModel::save,
                    onDelete = viewModel::delete,
                    onSettingsChange = viewModel::updateSettings
                )
            }
        }
    }
}
