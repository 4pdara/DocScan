package com.docscan.rebuild

import com.docscan.rebuild.model.*
import org.junit.Assert.*
import org.junit.Test

class DocumentNameTest {
    private fun word(text: String, left: Float, top: Float, height: Float, width: Float = .1f) = TextBox(text, left, top, left + width, top + height)

    @Test fun theLargestLineNearTheTopNamesTheDocument() {
        val words = listOf(word("12/11/2026", .7f, .03f, .015f), word("חשבונית", .55f, .08f, .04f), word("מס", .4f, .08f, .04f), word("1234", .25f, .08f, .04f, .12f),
            word("לכבוד", .7f, .15f, .02f), word("דנה", .55f, .15f, .02f), word("סה\"כ", .7f, .8f, .06f), word("1,250", .5f, .8f, .06f))
        // The total at the bottom is printed larger, but a title is near the top; Hebrew is joined from the right.
        assertEquals("חשבונית מס 1234", DocumentName.suggest(words))
        assertEquals("Annual Report", DocumentName.suggest(listOf(word("Annual", .1f, .1f, .05f), word("Report", .25f, .1f, .05f), word("page", .1f, .3f, .02f))))
        assertNull("only numbers", DocumentName.suggest(listOf(word("2026", .1f, .1f, .05f), word("15.00", .3f, .1f, .05f))))
        assertNull(DocumentName.suggest(emptyList()))
        assertEquals(20, DocumentName.suggest(listOf(word("A".repeat(50), .1f, .1f, .05f)), limit = 20)!!.length)
    }
}
