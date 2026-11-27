package io.github.univers629.millet.ui.model

import org.junit.Assert.assertEquals
import org.junit.Test

class DozeSessionLabelsTest {

    private fun session(
        start: Int,
        end: Int,
        charged: Boolean = false,
        apps: List<DozeAppCpuUiModel> = emptyList(),
        maint: Int = 0
    ) = DozeSessionUiModel(
        startMillis = 0L,
        endMillis = 1_000L,
        durationMillis = 1_000L,
        maintenanceCount = maint,
        startBatteryPct = start,
        endBatteryPct = end,
        chargedDuringSession = charged,
        topApps = apps
    )

    @Test
    fun chargingSessionReportsCharging() {
        assertEquals("充电中", session(80, 82, charged = true).batteryLabel())
    }

    @Test
    fun liveSessionReportsBatteryDelta() {
        assertEquals("91%→91%", session(91, 91).batteryLabel())
        assertEquals("80%→78%", session(80, 78).batteryLabel())
    }

    @Test
    fun backfilledSessionIsLabelledSeparately() {
        // 回填只有时间与维护次数：电量为 -1、没有 CPU 明细
        val backfilled = session(-1, -1)
        assertEquals(true, backfilled.isBackfilled())
        assertEquals("开机回填", backfilled.batteryLabel())
        assertEquals("维护 2 次 · 开机回填", session(-1, -1, maint = 2).summaryLabel())
    }

    @Test
    fun unknownBatteryStaysUnknown() {
        // 有 CPU 明细但电量缺失：不是回填，仍按未知处理
        val apps = listOf(
            DozeAppCpuUiModel(
                uid = 10296,
                packageName = "com.tencent.mm",
                cpuMillis = 10L,
                sessionCount = 1,
                label = "微信"
            )
        )
        assertEquals("电量未知", session(-1, -1, apps = apps).batteryLabel())
    }

    @Test
    fun chargingWinsOverBatteryRange() {
        assertEquals("充电中", session(50, 55, charged = true).batteryLabel())
    }

    @Test
    fun allSessionsBackfilledRequiresEverySessionBackfilled() {
        fun summary(vararg sessions: DozeSessionUiModel) = DozeSummaryUiModel(
            sessionCount = sessions.size,
            recentSessions = sessions.toList()
        )

        assertEquals(false, summary().allSessionsBackfilled())
        assertEquals(false, summary(session(-1, -1), session(80, 79)).allSessionsBackfilled())
        assertEquals(true, summary(session(-1, -1), session(-1, -1, maint = 3)).allSessionsBackfilled())
    }
}
