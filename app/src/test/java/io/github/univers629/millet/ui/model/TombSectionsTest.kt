package io.github.univers629.millet.ui.model

import io.github.univers629.millet.ui.data.InMemorySharedPreferences
import io.github.univers629.millet.ui.theme.UiThemeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TombSectionsTest {

    @Test
    fun parseFallsBackToAllSectionsAndDropsUnknownIds() {
        assertEquals(TombSection.allIds, TombSection.parse(null))
        assertEquals(setOf(TombSection.RANK.id), TombSection.parse(setOf(TombSection.RANK.id, "legacy")))
        assertEquals(emptySet<String>(), TombSection.parse(emptySet()))
    }

    @Test
    fun dozeSectionIsAddedOnceForExistingPreferencesAndKeepsLaterToggle() {
        val prefs = InMemorySharedPreferences()
        prefs.edit()
            .putStringSet("ui_tomb_sections", setOf(TombSection.RANK.id, TombSection.RECENT.id))
            .apply()

        val migrated = UiThemeState(prefs)
        assertEquals(
            setOf(
                TombSection.RANK.id,
                TombSection.RECENT.id,
                TombSection.DOZE.id,
                TombSection.NATIVE_RECORDS.id,
            ),
            migrated.tombSections,
        )

        // 用户主动关闭后，迁移标记已置位，重启不应再次打开
        migrated.updateTombSection(TombSection.DOZE.id, false)
        migrated.updateTombSection(TombSection.NATIVE_RECORDS.id, false)
        val relaunched = UiThemeState(prefs)
        assertFalse(TombSection.DOZE.id in relaunched.tombSections)
        assertFalse(TombSection.NATIVE_RECORDS.id in relaunched.tombSections)
        assertTrue(TombSection.RANK.id in relaunched.tombSections)
    }
}
