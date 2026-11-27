package io.github.univers629.millet.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DozeLiveStateTest {

    @Test
    fun encodeDecodeRoundTripKeepsBlockers() {
        val live = DozeLiveState(
            state = DozeLiveState.STATE_INACTIVE,
            forced = false,
            charging = true,
            screenOn = false,
            blockingConstraints = 2,
            blockerNames = listOf("alarm", "signal")
        )

        val decoded = DozeLiveState.decode(live.encode())

        assertEquals(live, decoded)
    }

    @Test
    fun stateNamesCoverAllFourGenerationStates() {
        val expected = mapOf(
            0 to "ACTIVE",
            1 to "INACTIVE",
            2 to "IDLE_PENDING",
            3 to "SENSING",
            4 to "LOCATING",
            5 to "IDLE",
            6 to "IDLE_MAINTENANCE",
            7 to "QUICK_DOZE"
        )

        expected.forEach { (state, name) ->
            assertEquals(name, liveState(state).stateName)
        }
        assertEquals("STATE_9", liveState(9).stateName)
    }

    @Test
    fun deepIdleAndPendingFlagsFollowStateNumbers() {
        assertTrue(liveState(DozeLiveState.STATE_IDLE).isDeepIdle)
        assertTrue(liveState(DozeLiveState.STATE_IDLE_MAINTENANCE).isDeepIdle)
        assertFalse(liveState(DozeLiveState.STATE_INACTIVE).isDeepIdle)

        assertTrue(liveState(DozeLiveState.STATE_IDLE_PENDING).isIdlePending)
        assertTrue(liveState(DozeLiveState.STATE_LOCATING).isIdlePending)
        assertFalse(liveState(DozeLiveState.STATE_IDLE).isIdlePending)
    }

    @Test
    fun summarySuffixNamesActiveBlockers() {
        val blocked = liveState(DozeLiveState.STATE_INACTIVE).copy(
            blockingConstraints = 2,
            blockerNames = listOf("alarm", "signal")
        )
        assertEquals("当前 INACTIVE · 阻塞 2（alarm、signal）", blocked.summarySuffix())

        val free = liveState(DozeLiveState.STATE_IDLE).copy(blockingConstraints = 0)
        assertEquals("当前 IDLE", free.summarySuffix())
    }

    @Test
    fun decodeRejectsBrokenPayloadAndClampsBlockerNames() {
        assertNull(DozeLiveState.decode(null))
        assertNull(DozeLiveState.decode(""))
        assertNull(DozeLiveState.decode("1|0|0"))
        assertNull(DozeLiveState.decode("x|0|0|0|0|"))

        val long = "n".repeat(40)
        val decoded = DozeLiveState.decode("1|0|0|0|1|$long")
        assertEquals(16, decoded?.blockerNames?.single()?.length)
    }

    private fun liveState(state: Int) = DozeLiveState(
        state = state,
        forced = false,
        charging = false,
        screenOn = true,
        blockingConstraints = 0,
        blockerNames = emptyList()
    )
}
