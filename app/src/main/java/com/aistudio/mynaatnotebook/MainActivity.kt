package com.aistudio.mynaatnotebook

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.aistudio.mynaatnotebook.data.LocaleHelper
import com.aistudio.mynaatnotebook.ui.NaatApp
import com.aistudio.mynaatnotebook.viewmodel.BackupViewModel
import com.aistudio.mynaatnotebook.viewmodel.DriveViewModel
import com.aistudio.mynaatnotebook.viewmodel.NaatViewModel
import com.aistudio.mynaatnotebook.viewmodel.SettingsViewModel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

  private val viewModel: NaatViewModel by viewModels()
  private val settingsViewModel: SettingsViewModel by viewModels()
  private val backupViewModel: BackupViewModel by viewModels()
  private val driveViewModel: DriveViewModel by viewModels()

  override fun attachBaseContext(newBase: Context) {
    // Apply the user's language choice before anything renders.
    super.attachBaseContext(LocaleHelper.wrap(newBase))
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    // Handle the branded splash screen transition (must be called before super.onCreate).
    installSplashScreen()
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()

    setContent {
      NaatApp(
        viewModel = viewModel,
        settingsViewModel = settingsViewModel,
        backupViewModel = backupViewModel,
        driveViewModel = driveViewModel
      )
    }
  }

  override fun onStart() {
    super.onStart()
    // Drive auto-sync (opt-in): uploads a fresh backup only when the
    // library changed since the last sync; a no-op otherwise.
    driveViewModel.maybeAutoSync()
  }

  override fun onStop() {
    super.onStop()
    // Don't release audio on rotation/config changes — only on real backgrounding
    if (!isChangingConfigurations) {
      viewModel.onAppBackgrounded()
    }
  }
}
