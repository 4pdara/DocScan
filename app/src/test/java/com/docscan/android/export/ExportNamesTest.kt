package com.docscan.android.export

import org.junit.Assert.*
import org.junit.Test

class ExportNamesTest {
    @Test fun preservesHebrewWithoutDuplicatingExtension() {
        assertEquals("חשבונית.pdf", ExportNames.pdf("חשבונית.PDF"))
    }
    @Test fun stripsPathSeparatorsAndControlCharacters() {
        val result = ExportNames.pdf("../folder\\file\n:name")
        assertFalse(result.contains('/')); assertFalse(result.contains('\\'))
        assertFalse(result.contains('\n')); assertFalse(result.contains(':'))
    }
    @Test fun givesBlankNamesAUsableDefaultAndBoundsLength() {
        assertEquals("DocScan.pdf", ExportNames.pdf("   "))
        assertTrue(ExportNames.pdf("a".repeat(300)).length <= 94)
    }
    @org.junit.Test fun unicodeNamesLeaveRoomForUniqueGallerySuffix() {
        val title = "書😀".repeat(100)
        val name = ExportNames.pdf(title).removeSuffix(".pdf")
        org.junit.Assert.assertTrue(name.toByteArray(Charsets.UTF_8).size <= 180)
        org.junit.Assert.assertFalse(name.last().isHighSurrogate())
        org.junit.Assert.assertTrue((name + "-" + java.util.UUID.randomUUID() + "-001.jpg").toByteArray(Charsets.UTF_8).size <= 255)
    }
}
