package fr.lulu.unison

import fr.lulu.unison.update.Versions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateTest {
    @Test fun newerPatchMinorMajor() {
        assertTrue(Versions.isNewer("1.0.1", "1.0.0"))
        assertTrue(Versions.isNewer("1.1.0", "1.0.9"))
        assertTrue(Versions.isNewer("2.0.0", "1.99.99"))
    }

    @Test fun sameOrOlderIsNotNewer() {
        assertFalse(Versions.isNewer("1.1.0", "1.1.0"))
        assertFalse(Versions.isNewer("1.0.9", "1.1.0"))
        assertFalse(Versions.isNewer("1.0", "1.0.0"))
    }

    @Test fun compareNumbersNotStrings() {
        assertTrue(Versions.isNewer("1.10.0", "1.9.0"))
    }

    @Test fun ignoresPrefixAndSuffix() {
        assertEquals(listOf(1, 2, 3), Versions.parse("v1.2.3"))
        assertEquals(listOf(1, 2, 3), Versions.parse("1.2.3-beta.1"))
        assertTrue(Versions.isNewer("v1.1.0", "1.0.0"))
    }
}
