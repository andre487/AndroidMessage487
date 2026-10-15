package life.andre.message487

import org.junit.Assert.*
import org.junit.Test

class SourcesScreenTest {
    @Test fun `search merges manual packages once and respects selection and case`() {
        val apps = listOf(AppSource("installed.z", "Zulu"), AppSource("installed.a", "Alpha"))
        val selected = setOf("installed.a", "manual.hidden")
        assertEquals(listOf("installed.a", "manual.hidden", "installed.z"),
            displayedSources(apps, selected, "", false).map { it.packageName })
        assertEquals(listOf("installed.a"), displayedSources(apps, selected, "ALP", true).map { it.packageName })
        assertEquals(listOf("manual.hidden"), displayedSources(apps, selected, "HIDDEN", true).map { it.packageName })
        assertTrue(displayedSources(apps, selected, "Zulu", true).isEmpty())
    }
}
