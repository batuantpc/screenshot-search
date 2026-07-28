package com.projectmemento.app.search

import org.junit.Assert.assertEquals
import org.junit.Test

class TextNormalizerTest {
    @Test
    fun `normalizes Turkish characters for local search`() {
        assertEquals(
            "kadikoy de kahvalti",
            TextNormalizer.normalize("Kadıköy'de kahvaltı"),
        )
    }
}
