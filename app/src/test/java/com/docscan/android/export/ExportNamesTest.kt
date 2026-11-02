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
}
