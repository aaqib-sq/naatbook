package com.aistudio.mynaatnotebook

import android.content.Context
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.fragment.app.FragmentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.aistudio.mynaatnotebook.data.LocaleHelper
import com.aistudio.mynaatnotebook.ui.NaatApp
import com.aistudio.mynaatnotebook.viewmodel.BackupViewModel
import com.aistudio.mynaatnotebook.viewmodel.DriveViewModel
import com.aistudio.mynaatnotebook.viewmodel.LockViewModel
import com.aistudio.mynaatnotebook.viewmodel.NaatViewModel
import com.aistudio.mynaatnotebook.viewmodel.SettingsViewModel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
// FragmentActivity (not plain ComponentActivity): BiometricPrompt requires it.
class MainActivity : FragmentActivity() {

  private val viewModel: NaatViewModel by viewModels()
  private val settingsViewModel: SettingsViewModel by viewModels()
  private val backupViewModel: BackupViewModel by viewModels()
  private val driveViewModel: DriveViewModel by viewModels()
  private val lockViewModel: LockViewModel by viewModels()

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
        // Layout direction is pinned to LTR for the whole app (user decision
        // 2026-10-15): choosing Urdu changes strings only; the structure
        // never mirrors. This makes live switch and cold start identical.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            NaatApp(
                viewModel = viewModel,
                settingsViewModel = settingsViewModel,
                backupViewModel = backupViewModel,
                driveViewModel = driveViewModel,
                lockViewModel = lockViewModel
            )
        }
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
