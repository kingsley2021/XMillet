package io.github.univers629.millet.system

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ForegroundStateTest {

    private var clock = 1_000L

    @After
    fun tearDown() {
        ForegroundState.installForTest(null)
        ForegroundState.installScreenStateForTest(null)
        ForegroundState.nowProvider = { 0L }
        SystemState.lastToFrontPackage = ""
    }

    private fun installAt(updatedAt: Long, packages: Set<String>) {
        ForegroundState.nowProvider = { clock }
        ForegroundState.installForTest(
            ForegroundState.Snapshot(
                updatedAtElapsed = updatedAt,
                visiblePackages = packages,
                visibleUids = emptySet(),
                processNames = emptyMap(),
            )
        )
    }

    @Test
    fun freshSnapshotReportsVisiblePackage() {
        installAt(updatedAt = 1_000L, packages = setOf("com.example.fg"))
        assertEquals(true, ForegroundState.isForeground("com.example.fg"))
        assertEquals(false, ForegroundState.isForeground("com.example.other"))
    }

    @Test
    fun expiredSnapshotIsTreatedAsUnknown() {
        installAt(updatedAt = 1_000L, packages = setOf("com.example.fg"))
        clock = 1_000L + 60_000L
        assertNull(ForegroundState.isForeground("com.example.fg"))
        assertNull(ForegroundState.processNameOf(42))
    }

    @Test
    fun missingSnapshotIsTreatedAsUnknown() {
        ForegroundState.installForTest(null)
        ForegroundState.nowProvider = { clock }
        assertNull(ForegroundState.isForeground("com.example.fg"))
        assertNull(ForegroundState.isForeground(null))
    }

    @Test
    fun snapshotFreshUntilTtlBoundary() {
        installAt(updatedAt = 1_000L, packages = setOf("com.example.fg"))
        clock = 1_000L + 8_000L
        assertEquals(true, ForegroundState.isForeground("com.example.fg"))
        clock = 1_000L + 8_001L
        assertNull(ForegroundState.isForeground("com.example.fg"))
    }

    @Test
    fun screenOffOverridesLastToFrontPackageAndRestoresOnScreenOn() {
        SystemState.lastToFrontPackage = "com.example.fg"
        ForegroundState.installScreenStateForTest(false)
        assertEquals(false, SystemState.isPackageCurrentlyForeground("com.example.fg"))

        ForegroundState.installScreenStateForTest(true)
        assertEquals(true, SystemState.isPackageCurrentlyForeground("com.example.fg"))
    }
}
