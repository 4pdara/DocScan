package com.docscan.rebuild

import com.docscan.rebuild.model.*
import org.junit.Assert.*
import org.junit.Test

class OcrOptionsTest {
    @Test fun searchTreatsSqlMetacharactersAsLiteralText() {
        assertEquals("%50\\%\\_\\\\book%", TextSearch.pattern(" 50%_\\book "))
        assertEquals("%שלום עולם%", TextSearch.pattern("שלום עולם"))
    }
    @Test fun layoutsDoNotRequireAnUnbundledOrientationModel() {
        assertEquals(listOf(3,4,6,11), OcrLayout.entries.map { it.segmentation })
        assertEquals(OcrLayout.AUTO, OcrOptions().layout)
        for (languages in listOf("heb", "eng", "heb+eng")) assertEquals(languages, OcrOptions(languages).languages)
    }
    @Test(expected = IllegalArgumentException::class) fun unbundledLanguagesAreRejected() { OcrOptions("osd") }
}
