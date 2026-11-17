package com.docscan.rebuild

import com.docscan.rebuild.model.BusinessCard
import com.docscan.rebuild.model.Receipt
import org.junit.Assert.*
import org.junit.Test

class CardAndReceiptTest {
    @Test fun aHebrewBusinessCard() {
        val card = BusinessCard.parse("""
            דנה לוי
            מנהלת שיווק
            אור טכנולוגיות בע"מ
            טל: 03-6123456
            נייד: 052-1234567
            dana@or-tech.co.il
            www.or-tech.co.il
            רחוב הרצל 12, תל אביב
        """.trimIndent())
        assertEquals("דנה לוי", card.name)
        assertEquals("מנהלת שיווק", card.title)
        assertEquals("אור טכנולוגיות בע\"מ", card.company)
        assertEquals(listOf("052-1234567", "03-6123456"), card.phones)
        assertEquals(listOf("dana@or-tech.co.il"), card.emails)
        assertEquals("www.or-tech.co.il", card.website)
        assertEquals("רחוב הרצל 12, תל אביב", card.address)
    }

    @Test fun anEnglishBusinessCard() {
        val card = BusinessCard.parse("John Smith\nSenior Engineer\nAcme Group Ltd\n+44 20 7946 0958\njohn.smith@acme.com\nhttps://acme.com")
        assertEquals("John Smith", card.name); assertEquals("Senior Engineer", card.title); assertEquals("Acme Group Ltd", card.company)
        assertEquals(listOf("+44 20 7946 0958"), card.phones); assertEquals("https://acme.com", card.website)
        assertTrue(BusinessCard.parse("").empty)
    }

    @Test fun aReceiptsTotalDateAndShop() {
        val receipt = Receipt.parse("""
            סופר השכונה
            רח' הגפן 4, חיפה
            16/11/2026 18:42
            חלב 3% 1 6.90
            לחם אחיד 2 13.80
            גבינה 250 גרם 1,024.50
            סה"כ ביניים 1,045.20
            הנחה 5.00
            סה"כ לתשלום 1,040.20
            מזומן 1,050.00
            עודף 9.80
        """.trimIndent())
        assertEquals("סופר השכונה", receipt.shop)
        assertEquals("16/11/2026", receipt.date)
        assertEquals("1040.20", receipt.total)
        assertTrue(receipt.amounts.containsAll(listOf("6.90", "13.80", "1024.50")))
        // Without a total line, the largest amount.
        assertEquals("42.00", Receipt.parse("Cafe\n2 coffee 24.00\ncake 42.00\n").total)
        assertEquals("19.90", Receipt.parse("SHOP\nItem 9.90\nTOTAL 19.90\nCASH 20.00").total)
        assertNull(Receipt.parse("no numbers here").total)
    }
}
