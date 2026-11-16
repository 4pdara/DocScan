package com.docscan.rebuild

import com.docscan.rebuild.model.*
import org.junit.Assert.*
import org.junit.Test

class SensitiveTextTest {
    /** Words laid out left to right on one line at [top], each 0.1 wide. */
    private fun line(top: Float, vararg words: String) = words.mapIndexed { i, w -> TextBox(w, .05f + i * .12f, top, .15f + i * .12f, top + .02f) }

    @Test fun israeliIdCheckDigit() {
        assertTrue(SensitiveText.israeliId("123456782"))
        assertTrue(SensitiveText.israeliId("000000018"))
        assertTrue("leading zero left out", SensitiveText.israeliId("00000018".trimStart('0').padStart(8, '0')))
        assertFalse(SensitiveText.israeliId("123456789"))
        assertFalse(SensitiveText.israeliId("000000000"))
        assertFalse(SensitiveText.israeliId("12a456782"))
    }

    @Test fun luhnCheck() {
        assertTrue(SensitiveText.luhn("4580458045804580"))
        assertTrue(SensitiveText.luhn("4111111111111111"))
        assertFalse(SensitiveText.luhn("4111111111111112"))
        assertFalse(SensitiveText.luhn("123"))
    }

    @Test fun findsPersonalDetailsSplitOverWordsAndKeepsOrdinaryNumbers() {
        val words = line(.1f, "ת.ז.", "123456782") + line(.2f, "טלפון:", "052-123", "4567") + line(.3f, "mail:", "dana.levi@example.co.il") +
            line(.4f, "4111", "1111", "1111", "1111") + line(.5f, "+44", "20", "7946", "0958") + line(.6f, "02-6234567") +
            line(.7f, "סכום", "1,250.00", "שנת", "2026", "חשבונית", "123456789") + line(.8f, "12345678-2")
        val found = SensitiveText.find(words)
        val kinds = found.map { it.kind to it.text }
        assertTrue(kinds.toString(), SensitiveText.Kind.ID to "123456782" in kinds)
        assertTrue(kinds.toString(), SensitiveText.Kind.PHONE to "052-123 4567" in kinds)
        assertTrue(kinds.toString(), SensitiveText.Kind.EMAIL to "dana.levi@example.co.il" in kinds)
        assertTrue(kinds.toString(), SensitiveText.Kind.CARD to "4111 1111 1111 1111" in kinds)
        assertTrue(kinds.toString(), SensitiveText.Kind.PHONE to "+44 20 7946 0958" in kinds)
        assertTrue(kinds.toString(), SensitiveText.Kind.PHONE to "02-6234567" in kinds)
        assertTrue(kinds.toString(), SensitiveText.Kind.ID to "12345678-2" in kinds)
        // An amount, a year and a number that fails the ID check stay visible.
        assertEquals(kinds.toString(), 7, found.size)
        assertTrue(found.none { it.top in .69f..0.71f })
        // The phone's box covers both words it was split into, and the area to hide has a margin.
        val phone = found.single { it.kind == SensitiveText.Kind.PHONE && it.text.startsWith("052") }
        assertEquals(.17f, phone.left, 1e-5f); assertEquals(.39f, phone.right, 1e-5f)
        val hide = phone.hide()
        assertTrue(hide.valid() && hide.a.x < phone.left && hide.b.x > phone.right && hide.a.y < phone.top && hide.b.y > phone.bottom)
        assertTrue(SensitiveText.find(emptyList()).isEmpty())
    }

    @Test fun hiddenAreasFollowTheirHalfWhenAPageIsSplit() {
        val left = HideMark(HideKind.BLACK, Vertex(.1f, .2f), Vertex(.3f, .3f))
        val right = HideMark(HideKind.MOSAIC, Vertex(.7f, .5f), Vertex(.9f, .6f))
        val across = HideMark(HideKind.BLACK, Vertex(.45f, .8f), Vertex(.55f, .85f))
        val recipe = EditRecipe(hides = listOf(left, right, across))
        val l = recipe.hidesForHalf(.5f, left = true); val r = recipe.hidesForHalf(.5f, left = false)
        assertEquals(2, l.size); assertEquals(2, r.size)
        // On the left half (0 .. 0.5 of the page), x doubles; each area is a little wider than before.
        assertTrue(l[0].a.x <= .2f && l[0].b.x >= .6f && l[0].a.y == .2f && l[0].b.y == .3f)
        assertTrue("the area across the split stays hidden on both halves", l[1].b.x >= 1f && r.any { it.a.x <= 0f && it.a.y == .8f })
        assertTrue(r.any { it.kind == HideKind.MOSAIC && it.a.x <= .4f && it.b.x >= .8f })
        // A page turned a quarter: the split runs across the turned page.
        val turned = recipe.turnedBy(1, 1.4f)
        assertEquals(1, turned.turns)
        val top = turned.hidesForHalf(.5f, left = true)
        assertTrue(top.all { it.valid() } && top.size == 2)
        // Turned all the way round, an area is back where it was.
        val back = EditRecipe(hides = listOf(left)).turnedBy(2, 1f).turnedBy(2, 1f).hides.single()
        assertEquals(.1f, back.a.x, 1e-5f); assertEquals(.2f, back.a.y, 1e-5f); assertEquals(.3f, back.b.x, 1e-5f); assertEquals(.3f, back.b.y, 1e-5f)
        val half = EditRecipe(hides = listOf(left)).turnedBy(1, 1f).hides.single()
        assertEquals(.7f, half.a.x, 1e-5f); assertEquals(.1f, half.a.y, 1e-5f); assertEquals(.8f, half.b.x, 1e-5f); assertEquals(.3f, half.b.y, 1e-5f)
    }
}
