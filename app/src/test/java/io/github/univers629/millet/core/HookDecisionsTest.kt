package io.github.univers629.millet.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HookDecisionsTest {

    @Test
    fun `bypassUpcomingAlarmWhileForced needs forced flag and a stuck state`() {
        // 强制推进 + 卡在 QUICK_DOZE_DELAY(7) 或 INACTIVE(1)：OS4/OS3 都在这里被闹钟判定打断。
        assertTrue(HookDecisions.bypassUpcomingAlarmWhileForced(true, HookDecisions.STATE_QUICK_DOZE_DELAY))
        assertTrue(HookDecisions.bypassUpcomingAlarmWhileForced(true, HookDecisions.STATE_INACTIVE))
        // 强制但状态不在这两档：ROM 的闹钟保护照常生效，避免把用户闹钟推迟。
        assertFalse(HookDecisions.bypassUpcomingAlarmWhileForced(true, 0))
        assertFalse(HookDecisions.bypassUpcomingAlarmWhileForced(true, 5))
        assertFalse(HookDecisions.bypassUpcomingAlarmWhileForced(true, null))
        // 未强制：无论状态如何都不接管。
        assertFalse(HookDecisions.bypassUpcomingAlarmWhileForced(false, HookDecisions.STATE_QUICK_DOZE_DELAY))
        assertFalse(HookDecisions.bypassUpcomingAlarmWhileForced(false, HookDecisions.STATE_INACTIVE))
        // 反射读不到 mForceIdle 时按「未强制」处理，保持 ROM 原行为。
        assertFalse(HookDecisions.bypassUpcomingAlarmWhileForced(null, HookDecisions.STATE_QUICK_DOZE_DELAY))
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
}
