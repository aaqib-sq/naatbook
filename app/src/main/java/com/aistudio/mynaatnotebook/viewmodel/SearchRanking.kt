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
    // Audit 03 M2: ranking compares with the same Arabic-script folding
    // the DAO applies, or variant spellings would rank as lyrics-only hits.
    val q = normalizeSearchText(query)
    if (q.isEmpty()) return results
    return results.sortedWith(compareBy { summary ->
        when {
            normalizeSearchText(summary.title).contains(q) -> 0
            summary.poet?.let { normalizeSearchText(it).contains(q) } == true -> 1
            else -> 2
        }
    })
}
