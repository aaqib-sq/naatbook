package com.aistudio.mynaatnotebook.viewmodel

/**
 * Audit 03 M2: Arabic-script search folding.
 *
 * Urdu/Arabic text reaches the library in many spellings of the same
 * word: with or without harakat (zabar/zer/pesh), Arabic yeh/kaf where
 * Urdu uses its own forms, hamza variants of alef, teh marbuta for heh,
 * and so on. Plain SQL LIKE treats every one of those as a different
 * string, so a correctly-typed query could find nothing. Both sides are
 * folded with the SAME rules before matching: this function folds the
 * query (and the ranking pass), and the DAO folds the stored columns
 * in SQL. Keep the two rule sets identical.
 */
fun normalizeSearchText(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return ""
    val out = StringBuilder(trimmed.length)
    for (ch in trimmed) {
        if (ch in SEARCH_DIACRITICS) continue
        out.append(SEARCH_FOLDS[ch] ?: ch)
    }
    return out.toString().lowercase()
}

/** Harakat and elongation marks: present or absent, the word is the same. */
private val SEARCH_DIACRITICS: Set<Char> = setOf(
    '\u0640', // tatweel
    '\u064B', '\u064C', '\u064D', // tanwin
    '\u064E', '\u064F', '\u0650', // zabar, pesh, zer
    '\u0651', '\u0652', // shadda, sukun
    '\u0670' // superscript alef
)

/**
 * Maps a (normalized) search query back onto the ORIGINAL text, so the
 * matched span can be highlighted exactly as typed. The DAO folds both
 * sides before matching (harakat stripped, variant letters folded), so a
 * plain indexOf on the original text would miss matches whose spelling
 * differs - this rebuilds the folded text while remembering, for every
 * folded character, the index it came from in the original, then maps
 * the folded match back. Returns the inclusive IntRange in original
 * indices, or null when the query does not occur in [original].
 */
fun findFoldedMatchRange(original: String, query: String): IntRange? {
    val q = normalizeSearchText(query)
    if (q.isEmpty()) return null
    val folded = StringBuilder(original.length)
    val origIndex = ArrayList<Int>(original.length)
    for (i in original.indices) {
        val ch = original[i]
        if (ch in SEARCH_DIACRITICS) continue
        folded.append((SEARCH_FOLDS[ch] ?: ch).lowercaseChar())
        origIndex.add(i)
    }
    val start = folded.toString().indexOf(q)
    if (start < 0) return null
    val endFolded = start + q.length - 1
    if (endFolded >= origIndex.size) return null
    return origIndex[start]..origIndex[endFolded]
}

/**
 * The first lyrics line containing [query], windowed around the match so
 * the highlighted word is always visible, with the highlight range inside
 * the returned text. Returns null when no line contains the query. The
 * window keeps ~24 characters of context on each side; a leading/trailing
 * ellipsis marks text cut off by the window.
 */
fun lyricsSnippet(lyrics: String, query: String): Pair<String, IntRange>? {
    val context = 24
    for (rawLine in lyrics.split('\n')) {
        val line = rawLine.trim()
        if (line.isEmpty()) continue
        val range = findFoldedMatchRange(line, query) ?: continue
        val winStart = maxOf(0, range.first - context)
        val winEnd = minOf(line.length, range.last + 1 + context)
        val prefix = if (winStart > 0) "\u2026 " else ""
        val suffix = if (winEnd < line.length) " \u2026" else ""
        val text = prefix + line.substring(winStart, winEnd) + suffix
        val hlStart = prefix.length + (range.first - winStart)
        val hlEnd = prefix.length + (range.last - winStart)
        return text to (hlStart..hlEnd)
    }
    return null
}

/** Variant letters folded to the form Urdu text is usually stored in. */
private val SEARCH_FOLDS: Map<Char, Char> = mapOf(
    '\u0622' to '\u0627', // alef madda -> alef
    '\u0623' to '\u0627', // alef hamza above -> alef
    '\u0625' to '\u0627', // alef hamza below -> alef
    '\u0671' to '\u0627', // alef wasla -> alef
    '\u0624' to '\u0648', // waw hamza -> waw
    '\u0626' to '\u06CC', // yeh hamza -> Urdu yeh
    '\u064A' to '\u06CC', // Arabic yeh -> Urdu yeh
    '\u0649' to '\u06CC', // alef maksura -> Urdu yeh
    '\u0643' to '\u06A9', // Arabic kaf -> Urdu kaf
    '\u0629' to '\u06C1', // teh marbuta -> heh
    '\u06BE' to '\u06C1' // heh doachashmee -> heh
)
