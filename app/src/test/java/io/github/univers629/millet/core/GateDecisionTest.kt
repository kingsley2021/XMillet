package io.github.univers629.millet.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GateDecisionTest {

    @Test
    fun `decideFreeze enforces 3 layers and allows message apps to freeze normally`() {
        val prefs = PrefsSnapshot(
            whiteAppSet = setOf("com.example.white"),
            messageAppSet = setOf("com.tencent.mm"),
            notFreezeWhenHasFgsSet = setOf("com.example.music", "com.example.forced"),
            forceFreezeSet = setOf("com.example.forced"),
        )

        assertEquals(
            GateReason.WHITE_LIST,
            GateDecision.decideFreeze(
                AppGateContext(10100, "com.example.white"),
                prefs,
                checkFgs = true,
            ),
        )
        assertEquals(
            GateReason.BUILTIN_PROTECT,
            GateDecision.decideFreeze(
                AppGateContext(10101, "com.tencent.soter.soterserver"),
                prefs,
                checkFgs = true,
            ),
        )
        assertEquals(
            GateReason.DEFAULT_IME,
            GateDecision.decideFreeze(
                AppGateContext(
                    uid = 10102,
                    packageName = "com.example.ime",
                    serviceRoleProvider = { ServiceRoleProtection.DEFAULT_IME },
                ),
                prefs,
                checkFgs = true,
            ),
        )
        assertEquals(
            GateReason.ACCESSIBILITY_SERVICE,
            GateDecision.decideFreeze(
                AppGateContext(
                    uid = 10103,
                    packageName = "com.example.a11y",
                    serviceRoleProvider = { ServiceRoleProtection.ACCESSIBILITY },
                ),
                prefs,
                checkFgs = true,
            ),
        )
        assertEquals(
            GateReason.UNKNOWN_SERVICE_ROLES,
            GateDecision.decideFreeze(
                AppGateContext(
                    uid = 10104,
                    packageName = "com.example.unknown",
                    serviceRoleProvider = { ServiceRoleProtection.UNKNOWN },
                ),
                prefs,
                checkFgs = true,
            ),
        )

        // Message apps must freeze normally (only woken transiently on PACKET/broadcast).
        assertEquals(
            GateReason.ALLOW_FREEZE,
            GateDecision.decideFreeze(
                AppGateContext(10105, "com.tencent.mm"),
                prefs,
                checkFgs = true,
            ),
        )

        // Soft FGS protection applies only when checkFgs=true and not force-frozen.
        assertEquals(
            GateReason.FOREGROUND_SERVICE,
            GateDecision.decideFreeze(
                AppGateContext(
                    uid = 10106,
                    packageName = "com.example.music",
                    hasForegroundService = { true },
                ),
                prefs,
                checkFgs = true,
            ),
        )
        assertEquals(
            GateReason.ALLOW_FREEZE,
            GateDecision.decideFreeze(
                AppGateContext(
                    uid = 10106,
                    packageName = "com.example.music",
                    hasForegroundService = { true },
                ),
                prefs,
                checkFgs = false,
            ),
        )
        assertEquals(
            GateReason.ALLOW_FREEZE,
            GateDecision.decideFreeze(
                AppGateContext(
                    uid = 10107,
                    packageName = "com.example.forced",
                    hasForegroundService = { true },
                ),
                prefs,
                checkFgs = true,
            ),
        )
    }

    @Test
    fun `decideFreeze evaluates FGS lambda lazily only when needed`() {
        var fgsChecked = false
        val prefs = PrefsSnapshot(
            whiteAppSet = setOf("com.example.white"),
            notFreezeWhenHasFgsSet = setOf("com.example.white", "com.example.music"),
        )

        GateDecision.decideFreeze(
            AppGateContext(
                uid = 10100,
                packageName = "com.example.white",
                hasForegroundService = {
                    fgsChecked = true
                    true
                },
            ),
            prefs,
            checkFgs = true,
        )
        assertFalse(fgsChecked)

        GateDecision.decideFreeze(
            AppGateContext(
                uid = 10101,
                packageName = "com.example.music",
                hasForegroundService = {
                    fgsChecked = true
                    true
                },
            ),
            prefs,
            checkFgs = false,
        )
        assertFalse(fgsChecked)

        val reason = GateDecision.decideFreeze(
            AppGateContext(
                uid = 10101,
                packageName = "com.example.music",
                hasForegroundService = {
                    fgsChecked = true
                    true
                },
            ),
            prefs,
            checkFgs = true,
        )
        assertTrue(fgsChecked)
        assertEquals(GateReason.FOREGROUND_SERVICE, reason)
    }

    @Test
    fun `decideThaw honors priority order and OS1 Broadcast_ normalization`() {
        val prefs = PrefsSnapshot(
            disableCharge = true,
            disableScreenOnThaw = true,
            blockThawSet = setOf("com.example.blocked", "com.tencent.mobileqq"),
            forbidSyncBinderSet = setOf("com.example.blocked"),
            ignoreNetworkWakeSet = setOf("com.example.blocked"),
        )

        // OS1 emits "Broadcast_" with trailing underscore: allowed for non-QQ blockThawSet apps.
        val os1Broadcast = GateDecision.decideThaw(
            AppGateContext(10200, "com.example.blocked", isUidFrozen = { true }),
            "Broadcast_",
            prefs,
        )
        assertEquals(ThawGateVerdict.Allow(ThawAllowReason.ALLOWED_BY_POLICY), os1Broadcast)

        // QQ "broadcast" still hits HARD_RULE.
        val qqBroadcast = GateDecision.decideThaw(
            AppGateContext(10201, "com.tencent.mobileqq", isUidFrozen = { true }),
            "broadcast",
            prefs,
        )
        assertEquals(ThawGateVerdict.Intercept(InterceptRule.HARD_RULE), qqBroadcast)

        // User-open and foreground always win.
        assertEquals(
            ThawGateVerdict.Allow(ThawAllowReason.USER_OPEN),
            GateDecision.decideThaw(
                AppGateContext(10200, "com.example.blocked", isUidFrozen = { true }),
                "Activity Start",
                prefs,
            ),
        )
        assertEquals(
            ThawGateVerdict.Allow(ThawAllowReason.CURRENTLY_FOREGROUND),
            GateDecision.decideThaw(
                AppGateContext(
                    uid = 10200,
                    packageName = "com.example.blocked",
                    isUidFrozen = { true },
                    isCurrentlyForeground = { true },
                ),
                "Sync Binder",
                prefs,
            ),
        )

        // Rule priority: FORBID_SYNC_BINDER, IGNORE_NETWORK, NO_CHARGE_THAW, NO_SCREEN_ON_THAW, BLOCK_THAW.
        assertEquals(
            ThawGateVerdict.Intercept(InterceptRule.FORBID_SYNC_BINDER),
            GateDecision.decideThaw(
                AppGateContext(10200, "com.example.blocked", isUidFrozen = { true }),
                "Sync Binder",
                prefs,
            ),
        )
        assertEquals(
            ThawGateVerdict.Intercept(InterceptRule.IGNORE_NETWORK),
            GateDecision.decideThaw(
                AppGateContext(10200, "com.example.blocked", isUidFrozen = { true }),
                "PACKET",
                prefs,
            ),
        )
        assertEquals(
            ThawGateVerdict.Intercept(InterceptRule.NO_CHARGE_THAW),
            GateDecision.decideThaw(
                AppGateContext(10200, "com.example.blocked", isUidFrozen = { true }),
                "CHARGE_CHANGE",
                prefs,
            ),
        )
        assertEquals(
            ThawGateVerdict.Intercept(InterceptRule.NO_SCREEN_ON_THAW),
            GateDecision.decideThaw(
                AppGateContext(10200, "com.example.blocked", isUidFrozen = { true }),
                "screen on",
                prefs,
            ),
        )
        assertEquals(
            ThawGateVerdict.Intercept(InterceptRule.BLOCK_THAW),
            GateDecision.decideThaw(
                AppGateContext(10200, "com.example.blocked", isUidFrozen = { true }),
                "alarm",
                prefs,
            ),
        )
    }

    @Test
    fun `decideThaw allows HyperOS 4 snake_case and multitask foreground thaws under blockThaw`() {
        val prefs = PrefsSnapshot(
            forceFreezeSet = setOf("com.example.app"),
            blockThawSet = setOf("com.example.app"),
            forbidSyncBinderSet = setOf("com.example.app"),
            ignoreNetworkWakeSet = setOf("com.example.app"),
            disableCharge = true,
            disableScreenOnThaw = true,
        )
        val ctx = AppGateContext(10250, "com.example.app", isUidFrozen = { true })

        val userOpenReasons = listOf(
            "activity_start",
            "activity_front",
            "activity_resume",
            "fg_activity",
            "freeform_mode",
            "FreeformMode",
            "multitask",
            "Multitask",
            "smallw",
            "small_win",
            "focus",
            "ui_bar",
            "process_start",
            "process start",
            "display_on",
            "Display_On",
            "VirtualDisplay",
            "carwith_cast",
            "app_function",
            "keycode",
        )
        for (reason in userOpenReasons) {
            assertEquals(
                "Expected USER_OPEN for reason=$reason",
                ThawGateVerdict.Allow(ThawAllowReason.USER_OPEN),
                GateDecision.decideThaw(ctx, reason, prefs),
            )
        }

        // HyperOS 4 snake_case sync binder and network wake still hit dedicated rules.
        assertEquals(
            ThawGateVerdict.Intercept(InterceptRule.FORBID_SYNC_BINDER),
            GateDecision.decideThaw(ctx, "sync_binder0", prefs),
        )
        assertEquals(
            ThawGateVerdict.Intercept(InterceptRule.FORBID_SYNC_BINDER),
            GateDecision.decideThaw(ctx, "check_binder", prefs),
        )
        assertEquals(
            ThawGateVerdict.Intercept(InterceptRule.IGNORE_NETWORK),
            GateDecision.decideThaw(ctx, "packet", prefs),
        )
        assertEquals(
            ThawGateVerdict.Intercept(InterceptRule.IGNORE_NETWORK),
            GateDecision.decideThaw(ctx, "signal_other9", prefs),
        )
        assertEquals(
            ThawGateVerdict.Intercept(InterceptRule.IGNORE_NETWORK),
            GateDecision.decideThaw(ctx, "network_connect", prefs),
        )

        // Pure blockThaw still suppresses background wakes while allowing foreground binder / same_uid.
        val blockOnlyPrefs = PrefsSnapshot(blockThawSet = setOf("com.example.app"))
        assertEquals(
            ThawGateVerdict.Allow(ThawAllowReason.ALLOWED_BY_POLICY),
            GateDecision.decideThaw(ctx, "fg_important", blockOnlyPrefs),
        )
        assertEquals(
            ThawGateVerdict.Allow(ThawAllowReason.ALLOWED_BY_POLICY),
            GateDecision.decideThaw(ctx, "same_uid", blockOnlyPrefs),
        )
        assertEquals(
            ThawGateVerdict.Allow(ThawAllowReason.ALLOWED_BY_POLICY),
            GateDecision.decideThaw(ctx, "sync_binder0", blockOnlyPrefs),
        )
        assertEquals(
            ThawGateVerdict.Intercept(InterceptRule.BLOCK_THAW),
            GateDecision.decideThaw(ctx, "execute_service", blockOnlyPrefs),
        )
        assertEquals(
            ThawGateVerdict.Intercept(InterceptRule.BLOCK_THAW),
            GateDecision.decideThaw(ctx, "mipush", blockOnlyPrefs),
        )
    }

    @Test
    fun `decideFreeze and decideThaw isolate dual app user 999`() {
        val prefs = PrefsSnapshot(
            whiteAppSet = setOf("com.example.app"),
            blockThawSet = setOf("com.example.app#999"),
        )
        assertEquals(
            GateReason.WHITE_LIST,
            GateDecision.decideFreeze(AppGateContext(10100, "com.example.app"), prefs, checkFgs = false),
        )
        assertEquals(
            GateReason.ALLOW_FREEZE,
            GateDecision.decideFreeze(AppGateContext(99910100, "com.example.app"), prefs, checkFgs = false),
        )
        assertEquals(
            ThawGateVerdict.Intercept(InterceptRule.BLOCK_THAW),
            GateDecision.decideThaw(
                AppGateContext(99910100, "com.example.app", isUidFrozen = { true }),
                "alarm",
                prefs,
            ),
        )
    }

    @Test
    fun `decideBroadcastAllow exempts message apps unless restricted`() {
        val prefs = PrefsSnapshot(
            messageAppSet = setOf("com.tencent.mm", "com.example.muted"),
            ignoreNetworkWakeSet = setOf("com.example.muted"),
        )
        assertEquals(
            BroadcastGateVerdict.AllowExempt,
            GateDecision.decideBroadcastAllow(
                AppGateContext(10150, "com.tencent.mm", isUidFrozen = { true }),
                prefs,
            ),
        )
        assertEquals(
            BroadcastGateVerdict.DelegateToRom,
            GateDecision.decideBroadcastAllow(
                AppGateContext(10151, "com.example.muted", isUidFrozen = { true }),
                prefs,
            ),
        )
    }

    @Test
    fun `decideScreenOnThawExempt allows wallpaper, autofill, foreground binder and unrestricted widgets`() {
        val prefs = PrefsSnapshot(
            disableScreenOnThaw = true,
            blockThawSet = setOf("com.example.blocked.widget"),
            forceFreezeSet = setOf("com.example.forced.widget"),
        )

        assertEquals(
            ThawGateVerdict.Allow(ThawAllowReason.SCREEN_ON_EXEMPT),
            GateDecision.decideThaw(
                AppGateContext(10301, "io.wallpaperengine.weclient", isUidFrozen = { true }, isActiveLiveWallpaper = { true }),
                "screen on",
                prefs,
            ),
        )
        assertEquals(
            ThawGateVerdict.Allow(ThawAllowReason.SCREEN_ON_EXEMPT),
            GateDecision.decideThaw(
                AppGateContext(10302, "com.x8bit.bitwarden", isUidFrozen = { true }, isActiveAutofillService = { true }),
                "screen on",
                prefs,
            ),
        )
        assertEquals(
            ThawGateVerdict.Allow(ThawAllowReason.SCREEN_ON_EXEMPT),
            GateDecision.decideThaw(
                AppGateContext(10303, "com.example.helper", isUidFrozen = { true }, isForegroundBinderBound = { true }),
                "screen on",
                prefs,
            ),
        )
        assertEquals(
            ThawGateVerdict.Allow(ThawAllowReason.SCREEN_ON_EXEMPT),
            GateDecision.decideThaw(
                AppGateContext(10304, "com.example.weather", isUidFrozen = { true }, isWidgetApp = { true }),
                "screen on",
                prefs,
            ),
        )

        // 显式加入 blockThawSet 或 forceFreezeSet 的桌面小组件仍遵守用户压制策略。
        assertEquals(
            ThawGateVerdict.Intercept(InterceptRule.NO_SCREEN_ON_THAW),
            GateDecision.decideThaw(
                AppGateContext(10305, "com.example.blocked.widget", isUidFrozen = { true }, isWidgetApp = { true }),
                "screen on",
                prefs,
            ),
        )
        assertEquals(
            ThawGateVerdict.Intercept(InterceptRule.NO_SCREEN_ON_THAW),
            GateDecision.decideThaw(
                AppGateContext(10306, "com.example.forced.widget", isUidFrozen = { true }, isWidgetApp = { true }),
                "screen on",
                prefs,
            ),
        )
    }

    @Test
    fun `screen-on exemption probes are not evaluated on freeze or non-screen-on thaw paths`() {
        var probesCalled = 0
        val prefs = PrefsSnapshot(disableScreenOnThaw = true, disableCharge = true)
        val ctx = AppGateContext(
            uid = 10310,
            packageName = "com.example.app",
            isUidFrozen = { true },
            isActiveLiveWallpaper = { probesCalled++; true },
            isActiveAutofillService = { probesCalled++; true },
            isForegroundBinderBound = { probesCalled++; true },
            isWidgetApp = { probesCalled++; true },
        )

        assertEquals(GateReason.ALLOW_FREEZE, GateDecision.decideFreeze(ctx, prefs, checkFgs = true))
        assertEquals(
            ThawGateVerdict.Intercept(InterceptRule.NO_CHARGE_THAW),
            GateDecision.decideThaw(ctx, "CHARGE_CHANGE", prefs),
        )
        assertEquals(0, probesCalled)
    }
}
