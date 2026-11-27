package io.github.univers629.millet.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HookDecisionsTest {

    @Test
    fun `skipIdlePendingTargetState uses LOCATING while forced and MAINTENANCE otherwise`() {
        assertEquals(HookDecisions.STATE_LOCATING, HookDecisions.skipIdlePendingTargetState(true))
        assertEquals(HookDecisions.STATE_IDLE_MAINTENANCE, HookDecisions.skipIdlePendingTargetState(false))
        assertEquals(HookDecisions.STATE_IDLE_MAINTENANCE, HookDecisions.skipIdlePendingTargetState(null))
    }

    @Test
    fun `bypassUpcomingAlarmWhileForced bypasses across all states only when forced`() {
        // 强制推进期间全状态短路，防止 force-idle 循环在中间态被闹钟判定打回并死锁。
        for (state in 0..HookDecisions.STATE_QUICK_DOZE_DELAY) {
            assertTrue(HookDecisions.bypassUpcomingAlarmWhileForced(true, state))
        }
        assertTrue(HookDecisions.bypassUpcomingAlarmWhileForced(true, null))
        // 未强制或读取失败时保持 ROM 原逻辑。
        assertFalse(HookDecisions.bypassUpcomingAlarmWhileForced(false, HookDecisions.STATE_QUICK_DOZE_DELAY))
        assertFalse(HookDecisions.bypassUpcomingAlarmWhileForced(false, HookDecisions.STATE_INACTIVE))
        assertFalse(HookDecisions.bypassUpcomingAlarmWhileForced(null, HookDecisions.STATE_QUICK_DOZE_DELAY))
    }

    @Test
    fun `bypassAlarmIdleUntilFuzz requires forced doze, enabled flag and idle-until alarm bit`() {
        val idleUntilFlag = HookDecisions.ALARM_FLAG_IDLE_UNTIL
        assertTrue(HookDecisions.bypassAlarmIdleUntilFuzz(true, dozeEnabled = true, alarmFlags = idleUntilFlag))
        assertTrue(HookDecisions.bypassAlarmIdleUntilFuzz(true, dozeEnabled = true, alarmFlags = idleUntilFlag or 1))
        // 普通闹钟或系统闹钟（FLAG_STANDALONE=1 | FLAG_WAKE_FROM_IDLE=2）不含 FLAG_IDLE_UNTIL，不干预。
        assertFalse(HookDecisions.bypassAlarmIdleUntilFuzz(true, dozeEnabled = true, alarmFlags = 0x3))
        assertFalse(HookDecisions.bypassAlarmIdleUntilFuzz(true, dozeEnabled = true, alarmFlags = 0x8))
        assertFalse(HookDecisions.bypassAlarmIdleUntilFuzz(true, dozeEnabled = true, alarmFlags = null))
        // 非强制或未启用 Doze 增强时不干预。
        assertFalse(HookDecisions.bypassAlarmIdleUntilFuzz(false, dozeEnabled = true, alarmFlags = idleUntilFlag))
        assertFalse(HookDecisions.bypassAlarmIdleUntilFuzz(null, dozeEnabled = true, alarmFlags = idleUntilFlag))
        assertFalse(HookDecisions.bypassAlarmIdleUntilFuzz(true, dozeEnabled = false, alarmFlags = idleUntilFlag))
    }

    @Test
    fun `clearBlockingConstraintsWhileForced applies only to forced pre-idle states`() {
        for (state in 1..4) {
            assertTrue(HookDecisions.clearBlockingConstraintsWhileForced(true, dozeEnabled = true, state = state))
        }
        // ACTIVE(0)、IDLE(5)、IDLE_MAINTENANCE(6)、QUICK_DOZE_DELAY(7) 与 null 不清零。
        for (state in listOf(0, 5, 6, 7, null)) {
            assertFalse(HookDecisions.clearBlockingConstraintsWhileForced(true, dozeEnabled = true, state = state))
        }
        assertFalse(HookDecisions.clearBlockingConstraintsWhileForced(false, dozeEnabled = true, state = 3))
        assertFalse(HookDecisions.clearBlockingConstraintsWhileForced(null, dozeEnabled = true, state = 3))
        assertFalse(HookDecisions.clearBlockingConstraintsWhileForced(true, dozeEnabled = false, state = 3))
    }

    @Test
    fun `dozeEventToState keeps only deep and normal events`() {
        // ROM 记录的深度事件：4=IDLE(5)、5=IDLE_MAINTENANCE(6)、1=回到活动态(0)。
        assertEquals(5, HookDecisions.dozeEventToState(4))
        assertEquals(6, HookDecisions.dozeEventToState(5))
        assertEquals(0, HookDecisions.dozeEventToState(1))
        // 浅睡事件（2/3）与空事件（0）不参与深度统计。
        assertNull(HookDecisions.dozeEventToState(0))
        assertNull(HookDecisions.dozeEventToState(2))
        assertNull(HookDecisions.dozeEventToState(3))
        assertNull(HookDecisions.dozeEventToState(9))
    }

    @Test
    fun `allowPowerSaveMutation follows the choice stored in the database`() {
        // 库里是「无限制」：放行添加，拦截自动移除。
        assertTrue(HookDecisions.allowPowerSaveMutation(isAdd = true, unrestrictedInDatabase = true))
        assertFalse(HookDecisions.allowPowerSaveMutation(isAdd = false, unrestrictedInDatabase = true))
        // 库里不是「无限制」：放行移除（用户已改回默认），拦截自动添加。
        assertTrue(HookDecisions.allowPowerSaveMutation(isAdd = false, unrestrictedInDatabase = false))
        assertFalse(HookDecisions.allowPowerSaveMutation(isAdd = true, unrestrictedInDatabase = false))
        // 读不到配置行时按「未知」处理，保持拦截。
        assertFalse(HookDecisions.allowPowerSaveMutation(isAdd = true, unrestrictedInDatabase = null))
        assertFalse(HookDecisions.allowPowerSaveMutation(isAdd = false, unrestrictedInDatabase = null))
    }

    @Test
    fun `clusterControlResult takes over only dual app uids`() {
        // 双开 uid：结果完全由开关决定，与 ROM 的 uid<=19999 判定无关。
        assertEquals(true, HookDecisions.clusterControlResult(99910277, freezeDualApp = true))
        assertEquals(false, HookDecisions.clusterControlResult(99910277, freezeDualApp = false))
        // 主用户应用保持 null，交回 ROM 判定。
        assertNull(HookDecisions.clusterControlResult(10277, freezeDualApp = true))
        // 边界与非法 uid 同样不接管。
        assertNull(HookDecisions.clusterControlResult(99901000, freezeDualApp = true))
        assertNull(HookDecisions.clusterControlResult(99990001, freezeDualApp = true))
        assertNull(HookDecisions.clusterControlResult(0, freezeDualApp = true))
        assertNull(HookDecisions.clusterControlResult(-1, freezeDualApp = true))
    }

    @Test
    fun `acceptLedgerReport keeps the more complete ledger`() {
        val complete = HookInstallRegistry.Wire(installed = 27, total = 31)
        val partial = HookInstallRegistry.Wire(installed = 22, total = 26)

        // 先到完整、后到残缺：残缺账本被丢弃，界面上不会少几个点位。
        assertFalse(HookDecisions.acceptLedgerReport(partial, complete))
        // 正常顺序：残缺之后补上完整账本，必须采纳。
        assertTrue(HookDecisions.acceptLedgerReport(complete, partial))
        // 同一份规格表走完后的 installed 变化是真实安装结果，照常采纳。
        assertTrue(
            HookDecisions.acceptLedgerReport(
                HookInstallRegistry.Wire(installed = 26, total = 31),
                complete
            )
        )
        // 还没有任何缓存时，第一份账本无论是否完整都要收下。
        assertTrue(HookDecisions.acceptLedgerReport(partial, HookInstallRegistry.Wire()))
        // 对端没上报（total = -1）不算残缺，不阻塞已有缓存。
        assertTrue(HookDecisions.acceptLedgerReport(HookInstallRegistry.Wire(), complete))
    }

    @Test
    fun `clampScreenOffFreezeDelayMs clamps long delays only when enabled`() {
        assertEquals(62_000, HookDecisions.clampScreenOffFreezeDelayMs(62_000, fastScreenOffFreeze = false))
        assertEquals(3_000, HookDecisions.clampScreenOffFreezeDelayMs(3_000, fastScreenOffFreeze = false))

        assertEquals(
            HookDecisions.FAST_SCREEN_OFF_FREEZE_MS,
            HookDecisions.clampScreenOffFreezeDelayMs(62_000, fastScreenOffFreeze = true),
        )
        assertEquals(
            HookDecisions.FAST_SCREEN_OFF_FREEZE_MS,
            HookDecisions.clampScreenOffFreezeDelayMs(10_000, fastScreenOffFreeze = true),
        )
        assertEquals(5_000, HookDecisions.clampScreenOffFreezeDelayMs(5_000, fastScreenOffFreeze = true))
        assertEquals(1_500, HookDecisions.clampScreenOffFreezeDelayMs(1_500, fastScreenOffFreeze = true))
        assertEquals(0, HookDecisions.clampScreenOffFreezeDelayMs(0, fastScreenOffFreeze = true))
        assertEquals(-1, HookDecisions.clampScreenOffFreezeDelayMs(-1, fastScreenOffFreeze = true))
    }

    @Test
    fun `shouldSkipFrozenBroadcast allows core network state actions and skips others`() {
        val networkActions = listOf(
            "android.net.conn.CONNECTIVITY_CHANGE",
            "android.net.wifi.STATE_CHANGE",
            "android.net.wifi.WIFI_STATE_CHANGED",
            "android.net.conn.INET_CONDITION_ACTION",
        )
        for (action in networkActions) {
            assertTrue(HookDecisions.isNetworkStateBroadcastAction(action))
            assertFalse(HookDecisions.shouldSkipFrozenBroadcast(action))
        }
        for (action in listOf(
            "android.intent.action.BATTERY_CHANGED",
            "android.intent.action.SCREEN_ON",
            "com.example.CUSTOM_ACTION",
            "",
            null,
        )) {
            assertFalse(HookDecisions.isNetworkStateBroadcastAction(action))
            assertTrue(HookDecisions.shouldSkipFrozenBroadcast(action))
        }
    }
}
