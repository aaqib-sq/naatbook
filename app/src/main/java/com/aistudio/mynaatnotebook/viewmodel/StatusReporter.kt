package com.aistudio.mynaatnotebook.viewmodel

import androidx.annotation.StringRes
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A user-facing status/toast message.
 *
 * ViewModels cannot resolve string resources (no Context, no @Composable scope),
 * so messages are modelled as data and resolved by the UI layer:
 * - [Text] carries an already-resolved dynamic string (e.g. exception details).
 * - [Res] carries a string resource ID plus format arguments, resolved via
 *   `Context.getString(resId, *args)` by the collector. Arguments may themselves
 *   be [StatusMessage] for nested fallbacks (e.g. "database error").
 */
sealed interface StatusMessage {
    data class Text(val text: String) : StatusMessage
    data class Res(
        @StringRes val resId: Int,
        val args: List<Any> = emptyList()
    ) : StatusMessage
}

/**
 * Process-wide status/toast message bus.
 *
 * Every feature ViewModel reports user-facing status through this single channel,
 * so the UI collects exactly one flow no matter which ViewModel produced the message.
 * NaatViewModel keeps its `statusMessage`/`clearStatusMessage()` API as a thin
 * delegate for backward compatibility with existing screens.
 */
@Singleton
class StatusReporter @Inject constructor() {
    private val _message = MutableStateFlow<StatusMessage?>(null)
    val message: StateFlow<StatusMessage?> = _message.asStateFlow()

    fun show(message: StatusMessage?) {
        _message.value = message
    }

    /** Show a string resource, with optional format arguments. */
    fun show(@StringRes resId: Int, vararg args: Any) {
        _message.value = StatusMessage.Res(resId, args.toList())
    }

    /** Show an already-resolved dynamic string. */
    fun showText(text: String) {
        _message.value = StatusMessage.Text(text)
    }

    fun clear() {
        _message.value = null
    }
}

/**
 * Resolves a [StatusMessage] to a display string using [Context.getString].
 * Nested [StatusMessage] arguments (e.g. error fallbacks) are resolved recursively.
 *
 * Lifted out of NaatApp (where it was private) so any UI surface — e.g. the
 * font-import error dialog — can render a [StatusMessage] the same way toasts do.
 */
fun StatusMessage.resolve(context: Context): String = when (this) {
    is StatusMessage.Text -> text
    is StatusMessage.Res -> context.getString(
        resId,
        *args.map { if (it is StatusMessage) it.resolve(context) else it }.toTypedArray()
    )
}
