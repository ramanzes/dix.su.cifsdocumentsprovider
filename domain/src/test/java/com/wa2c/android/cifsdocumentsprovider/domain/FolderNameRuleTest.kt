package com.wa2c.android.cifsdocumentsprovider.domain

import com.wa2c.android.cifsdocumentsprovider.domain.model.FolderNameRule
import com.wa2c.android.cifsdocumentsprovider.domain.model.FolderNameRule.Problem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FolderNameRuleTest {

    @Test
    fun valid() {
        listOf("a", "Photos", "photos2026", "ABC123xyz", "a".repeat(32)).forEach {
            assertNull(it, FolderNameRule.check(it))
        }
    }

    @Test
    fun empty() {
        assertEquals(Problem.EMPTY, FolderNameRule.check(""))
    }

    @Test
    fun tooLong() {
        assertEquals(Problem.TOO_LONG, FolderNameRule.check("a".repeat(33)))
    }

    @Test
    fun invalidCharacters() {
        listOf("My Photos", " a", "a ", "a_b", "a-b", "a.b", "Фото", "café", "a/b").forEach {
            assertEquals(it, Problem.INVALID_CHARACTERS, FolderNameRule.check(it))
        }
    }
}
