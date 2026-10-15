package com.aistudio.mynaatnotebook.data

/**
 * How often Drive auto-sync may fire. Persisted as a plain string in
 * SettingsStore ("drive_sync_interval") so the choice survives upgrades;
 * unknown or missing values fall back to [WEEKLY].
 */
enum class DriveSyncInterval(val key: String) {
    OPEN("open"),
    DAILY("daily"),
    WEEKLY("weekly");

    companion object {
        fun fromKey(key: String?): DriveSyncInterval =
            DriveSyncInterval.values().firstOrNull { it.key == key } ?: WEEKLY
    }
}

private const val DAY_MS = 24L * 60L * 60L * 1000L
private const val WEEK_MS = 7L * DAY_MS

/**
 * Pure gating rule for automatic Drive syncs. Only the auto path goes through
 * here: manual "Sync now" never calls this and is never gated.
 *
 * Auto-sync fires when the account is connected, the feature is on, the
 * library changed since the last sync, and the chosen interval elapsed.
 * A [lastSyncAtMs] of 0 (never synced) always counts as elapsed.
 */
fun shouldAutoSync(
    interval: DriveSyncInterval,
    connected: Boolean,
    autoSyncOn: Boolean,
    hashChanged: Boolean,
    lastSyncAtMs: Long,
    nowMs: Long
): Boolean {
    if (!connected || !autoSyncOn || !hashChanged) return false
    if (lastSyncAtMs <= 0L) return true
    return when (interval) {
        DriveSyncInterval.OPEN -> true
        DriveSyncInterval.DAILY -> nowMs - lastSyncAtMs >= DAY_MS
        DriveSyncInterval.WEEKLY -> nowMs - lastSyncAtMs >= WEEK_MS
    }
}
