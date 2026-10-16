package com.aistudio.mynaatnotebook.viewmodel

/**
 * Reader scroll-memory restore math (audit TOP 2), kept pure for testing.
 *
 * Returns the offset to scroll to when the reader opens, or 0 when there is
 * nothing worth restoring: nothing saved yet (savedPx <= 0), or the content
 * fits the viewport (maxPx <= 0), in which case scrolling is meaningless.
 * An overshooting saved offset is clamped to the current content height so an
 * edited-shorter entry can never jump past its end.
 */
internal fun clampedRestoreOffset(savedPx: Int, maxPx: Int): Int {
    if (savedPx <= 0 || maxPx <= 0) return 0
    return minOf(savedPx, maxPx)
}
