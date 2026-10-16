package com.aistudio.mynaatnotebook.viewmodel

import com.aistudio.mynaatnotebook.data.NaatSummary

/**
 * Stage-2 UX audit: relevance ranking for Library search.
 *
 * The DAO still does the matching (title/poet/lyrics, newest-first) — this
 * only re-orders the matches so title hits surface first, then poet hits,
 * then lyrics-only hits. Kotlin's sortedWith is stable, so the DAO's
 * newest-first order is preserved inside each tier.
 */
fun rankSearchResults(results: List<NaatSummary>, query: String): List<NaatSummary> {
    val q = query.trim()
    if (q.isEmpty()) return results
    return results.sortedWith(compareBy { summary ->
        when {
            summary.title.contains(q, ignoreCase = true) -> 0
            summary.poet?.contains(q, ignoreCase = true) == true -> 1
            else -> 2
        }
    })
}
