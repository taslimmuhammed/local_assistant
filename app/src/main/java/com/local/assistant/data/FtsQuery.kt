package com.local.assistant.data

/**
 * Turns free text into a safe FTS5 MATCH expression.
 *
 * FTS5 treats `"`, `*`, `(`, `-`, `:` and `^` as query operators, and the text
 * arriving here is written by users and by the model, so it contains all of them
 * routinely. Every token is stripped to word characters and quoted, which means a
 * stray apostrophe or a hyphenated phrase can never be parsed as syntax.
 *
 * Terms are OR-ed rather than AND-ed: recall here answers "find anything about
 * this", and requiring every word would miss the paraphrase that recall is for.
 *
 * Combining marks count as word characters. Indic scripts write vowel signs as
 * separate marks, so treating them as separators would shatter every word into
 * single code points and the length filter would then discard the lot.
 */
object FtsQuery {
    private const val MAX_TERMS = 12

    fun build(raw: String): String? {
        val terms = raw.split(Regex("[^\\p{L}\\p{N}\\p{M}]+"))
            .filter { it.length > 1 }
            .take(MAX_TERMS)
        if (terms.isEmpty()) return null
        return terms.joinToString(" OR ") { "\"$it\"" }
    }
}
