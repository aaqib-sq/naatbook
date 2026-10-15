package com.aistudio.mynaatnotebook.data

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * App-language override. The choice lives in DataStore via [SettingsStore] and
 * is mirrored into a plain SharedPreferences file so [wrap] can read it
 * synchronously inside attachBaseContext, before Hilt and DataStore exist.
 * "system" means: follow the phone, apply no override.
 */
object LocaleHelper {

    const val SYSTEM = "system"
    const val ENGLISH = "en"
    const val URDU = "ur"

    private const val SYNC_PREFS = "locale_sync"
    private const val KEY = "app_language"

    fun readChoice(context: Context): String =
        context.getSharedPreferences(SYNC_PREFS, Context.MODE_PRIVATE)
            .getString(KEY, SYSTEM) ?: SYSTEM

    fun writeChoice(context: Context, choice: String) {
        context.getSharedPreferences(SYNC_PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, choice).apply()
    }

    /** Returns a locale-wrapped context when the user picked a fixed language.
     *  Layout direction is ALWAYS pinned to LTR: selecting Urdu changes
     *  strings only; the UI structure never mirrors (user decision
     *  2026-10-15). Pinning here kills the old wandering-direction bug at
     *  its root — no attachBaseContext path can carry an RTL direction in,
     *  on cold start or after recreate. */
    fun wrap(base: Context): Context {
        val choice = readChoice(base)
        if (choice == SYSTEM) return base
        val locale = Locale(choice)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        // LTR is pinned regardless of the chosen locale (see above).
        config.setLayoutDirection(Locale.ENGLISH)
        return base.createConfigurationContext(config)
    }
}
