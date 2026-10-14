package com.aistudio.mynaatnotebook

import android.app.Application
import android.content.Context
import com.aistudio.mynaatnotebook.data.LocaleHelper
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class NaatBookApplication : Application() {

    override fun attachBaseContext(base: Context) {
        // App-wide language choice (also covers toasts and the playback
        // notification, which resolve strings from the application context).
        super.attachBaseContext(LocaleHelper.wrap(base))
    }
}
