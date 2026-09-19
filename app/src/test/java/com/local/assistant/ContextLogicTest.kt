package com.local.assistant

import com.local.assistant.context.ContextBudget
import com.local.assistant.context.StreamAccumulator
import com.local.assistant.data.FtsQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextBudgetTest {

    @Test
    fun `slices leave headroom and never oversubscribe the window`() {
        val budget = ContextBudget.from(4096)
        val planned = budget.systemTokens + budget.summaryTokens +
            budget.recentTokens + budget.responseTokens
        assertTrue(
            "slices sum to $planned which exceeds the 4096 ceiling",
            planned <= budget.usableCeiling
        )
    }

    @Test
    fun `compaction starts well before the window is full`() {
        val budget = ContextBudget.from(4096)
        assertTrue(budget.compactionWatermark < budget.hardLimit)
        assertTrue(budget.hardLimit < budget.usableCeiling)
        // Compaction has to prefill the turns it is folding, so it needs room to
        // run. Anywhere past about two thirds is too late to be safe; well under
        // half wastes the window on needless summarising.
        val share = budget.compactionWatermark.toDouble() / budget.usableCeiling
        assertTrue("watermark at $share of the window", share in 0.40..0.65)
    }

    @Test
    fun `a small measured ceiling still yields workable budgets`() {
        // The reported failure mode has E2B dying near 2000 tokens despite being
        // configured for 4096, so a tiny window is a real case, not a hypothetical.
        val budget = ContextBudget.from(1800)
        assertTrue(budget.summaryTokens > 0)
        assertTrue(budget.recentTokens > 0)
        assertTrue(budget.responseTokens > 0)
        assertTrue(budget.summaryChars > 0)
    }

    @Test
    fun `budgets scale with the measured ceiling`() {
        val small = ContextBudget.from(2048)
        val large = ContextBudget.from(8192)
        assertTrue(large.recentTokens > small.recentTokens)
        assertTrue(large.summaryChars > small.summaryChars)
    }
}

class StreamAccumulatorTest {

    @Test
    fun `incremental fragments are passed through unchanged`() {
        val acc = StreamAccumulator()
        assertEquals("Hello", acc.consume("Hello"))
        assertEquals(" there", acc.consume(" there"))
        assertEquals("Hello there", acc.text())
    }

    @Test
    fun `cumulative emissions are reduced to just the new tail`() {
        // Some streaming APIs resend the whole response each tick. Appending those
        // verbatim would produce "HelloHello there".
        val acc = StreamAccumulator()
        assertEquals("Hello", acc.consume("Hello"))
        assertEquals(" there", acc.consume("Hello there"))
        assertEquals("Hello there", acc.text())
    }

    @Test
    fun `a repeated identical emission yields nothing`() {
        val acc = StreamAccumulator()
        acc.consume("Hello")
        assertEquals("", acc.consume("Hello"))
        assertEquals("Hello", acc.text())
    }

    @Test
    fun `empty chunks are ignored`() {
        val acc = StreamAccumulator()
        assertEquals("", acc.consume(""))
        assertEquals("", acc.text())
    }
}

class FtsQueryTest {

    @Test
    fun `terms are quoted and OR-ed`() {
        assertEquals("\"italian\" OR \"place\"", FtsQuery.build("italian place"))
    }

    @Test
    fun `operator characters cannot escape into query syntax`() {
        // Unquoted, each of these would be parsed by FTS5 rather than matched.
        val query = FtsQuery.build("""NEAR("a" b) AND c* -d OR: ^e""")
        assertTrue(query!!.startsWith("\""))
        assertTrue(query.none { it == '*' || it == '(' || it == '^' })
    }

    @Test
    fun `apostrophes and hyphens in ordinary words are handled`() {
        val query = FtsQuery.build("don't use well-known phrases")
        assertEquals("\"don\" OR \"use\" OR \"well\" OR \"known\" OR \"phrases\"", query)
    }

    @Test
    fun `single characters and punctuation-only input produce no query`() {
        assertNull(FtsQuery.build("a b c"))
        assertNull(FtsQuery.build("!!! ???"))
        assertNull(FtsQuery.build(""))
    }

    @Test
    fun `very long input is capped`() {
        val query = FtsQuery.build((1..50).joinToString(" ") { "word$it" })!!
        assertEquals(12, query.split(" OR ").size)
    }

    @Test
    fun `non-latin scripts survive tokenisation`() {
        val query = FtsQuery.build("കൊച്ചി യാത്ര")
        assertTrue(query!!.contains("കൊച"))
    }
}
