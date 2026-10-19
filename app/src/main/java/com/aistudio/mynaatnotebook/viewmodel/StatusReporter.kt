package com.aistudio.mynaatnotebook.viewmodel

import androidx.annotation.StringRes
import android.content.Context
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
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
 * Messages are queued (audit 03 M13): a burst of receipts is delivered in
 * order instead of the latest one silently replacing the rest.
 */
@Singleton
class StatusReporter @Inject constructor() {
    // Audit 03 M13: the old single-slot StateFlow conflated rapid messages —
    // a second show() landing before collection silently ate the first
    // (a success receipt could vanish behind the message behind it). A
    // queue keeps every message, in order; the UI drains it one by one.
    private val queue = Channel<StatusMessage>(Channel.UNLIMITED)
    val messages: Flow<StatusMessage> = queue.receiveAsFlow()

    fun show(message: StatusMessage?) {
        if (message == null) {
            clear()
            return
        }
        queue.trySend(message)
    }

    /** Show a string resource, with optional format arguments. */
    fun show(@StringRes resId: Int, vararg args: Any) {
        queue.trySend(StatusMessage.Res(resId, args.toList()))
    }

    /** Show an already-resolved dynamic string. */
    fun showText(text: String) {
        queue.trySend(StatusMessage.Text(text))
    }

    /** Drop messages that have not been shown yet. */
    fun clear() {
        while (queue.tryReceive().isSuccess) {
            // Drain pending messages.
        }
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
