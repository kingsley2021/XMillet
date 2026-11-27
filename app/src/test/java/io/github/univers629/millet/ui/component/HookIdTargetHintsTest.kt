package io.github.univers629.millet.ui.component

import io.github.univers629.millet.core.RomCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 校验点位对照表覆盖完整、条目均为「类#方法」格式，且包含全部深睡观测点。
 */
class HookIdTargetHintsTest {

    @Test
    fun everyHintNamesAHostClassAndMethod() {
        assertTrue("点位对照表过小，可能没跟着新增点位更新", HOOK_ID_TARGET_HINTS.size >= 70)

        HOOK_ID_TARGET_HINTS.forEach { (id, target) ->
            assertTrue("$id 的对照值不应为空", target.isNotBlank())
            assertTrue("$id 的对照值应形如 类#方法，实际为 $target", target.contains('#'))
            assertTrue("$id 的对照值不应重复编号", target != id)
        }
    }

    @Test
    fun deepDozeObservationHooksAllHaveHints() {
        RomCapabilities.DOZE_OBSERVATION_HOOKS.forEach { id ->
            assertTrue("深睡观测点 $id 缺少对照", HOOK_ID_TARGET_HINTS.containsKey(id))
        }
    }

    @Test
    fun frequentlyUnsupportedHooksAreDescribed() {
        // HyperOS 1 上常见的未支持点位；确保诊断页能映射到对应宿主方法。
        val expected = mapOf(
            "S22" to "GreezeManagerService#dispatchThawEvent",
            "S24" to "GreezeManagerService#isAllowBroadcast",
            "S28" to "AurogonImmobulusMode#isUidValid",
            "S29" to "GreezeManagerService#checkStateForScrOff",
            "S32_CLOUD_FROZEN_WL" to "ListAppsManager#dealCloudWhiteList",
            "S33_POLICY_ALLOW_FREEZE" to "PolicyMaker#isAllowFreeze",
            "S41_ALARM_ADJUST_IDLE_UNTIL" to "AlarmManagerService#adjustIdleUntilTime",
            "S42_DOZE_CONSTRAINTS" to "DeviceIdleController#updateActiveConstraintsLocked",
            "P28_NO_RESTRICT" to "ActiveStateController#dealNoRestrictApp"
        )

        expected.forEach { (id, target) ->
            assertEquals(target, HOOK_ID_TARGET_HINTS[id])
        }
    }
}
