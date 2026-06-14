package com.pokebinder.scanner.data

import com.pokebinder.scanner.model.CardLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QueryAliasesTest {
    @Test
    fun koreanNamePrioritizesJapaneseAliasForJapaneseSearch() {
        val variants = QueryAliases.expand("리자몽", CardLanguage.JAPANESE)

        assertEquals("リザードン", variants.first())
        assertTrue("Charizard" in variants)
    }

    @Test
    fun suffixIsPreservedAcrossLanguages() {
        val variants = QueryAliases.expand("리자몽 ex", CardLanguage.JAPANESE)

        assertEquals("リザードンex", variants.first())
        assertTrue("Charizard ex" in variants)
    }
}
