package io.github.univers629.millet.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DozeRetryPolicyTest {

    @Test
    fun schedulesOnlyWhenScreenOffNotChargingAfterOptIn() {
        assertTrue(
            DozeRetryPolicy.shouldSchedule(
                optIn = true,
                dozeEnabled = true,
                screenOn = false,
                charging = false,
                deepEntered = false,
                retriedThisCycle = false,
            )
        )
    }

    @Test
    fun refusesWhenOptInMissing() {
        assertFalse(
            DozeRetryPolicy.shouldSchedule(
                optIn = false,
                dozeEnabled = true,
                screenOn = false,
                charging = false,
                deepEntered = false,
                retriedThisCycle = false,
            )
        )
    }

    @Test
    fun refusesWhenDozeDisabledOrAlreadyDeepOrAlreadyRetried() {
        assertFalse(DozeRetryPolicy.shouldSchedule(true, false, false, false, false, false))
        assertFalse(DozeRetryPolicy.shouldSchedule(true, true, false, false, deepEntered = true, retriedThisCycle = false))
        assertFalse(DozeRetryPolicy.shouldSchedule(true, true, false, false, deepEntered = false, retriedThisCycle = true))
    }

    @Test
    fun refusesWhenScreenOnOrChargingOrUnknown() {
        assertFalse(DozeRetryPolicy.shouldSchedule(true, true, screenOn = true, charging = false, deepEntered = false, retriedThisCycle = false))
        assertFalse(DozeRetryPolicy.shouldSchedule(true, true, screenOn = false, charging = true, deepEntered = false, retriedThisCycle = false))
        assertFalse(DozeRetryPolicy.shouldSchedule(true, true, screenOn = null, charging = false, deepEntered = false, retriedThisCycle = false))
        assertFalse(DozeRetryPolicy.shouldSchedule(true, true, screenOn = false, charging = null, deepEntered = false, retriedThisCycle = false))
    }

    @Test
    fun retryDelayOutlastsSystemAlarmWindow() {
        // ROM 的 MIN_TIME_TO_ALARM 是 10 分钟，补试延迟必须大于它。
        assertEquals(11 * 60 * 1000L, DozeRetryPolicy.RETRY_DELAY_MILLIS)
    }
}
