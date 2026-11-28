package io.github.univers629.millet.system

import io.github.univers629.millet.core.FreezeReasonClassifier
import io.github.univers629.millet.core.InterceptRule
import io.github.univers629.millet.core.StatsEventKind
import io.github.univers629.millet.core.StatsWindow
import io.github.univers629.millet.core.WakeCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime

class FreezeStatsRecorderTest {

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    private val testZone: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun millisAt(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int = 0,
        second: Int = 0,
    ): Long = ZonedDateTime.of(year, month, day, hour, minute, second, 0, testZone)
        .toInstant()
        .toEpochMilli()

    @Test
    fun `reason classifier matches FreezeGateHooks semantics`() {
        assertTrue(FreezeReasonClassifier.isToFrontReason("toFrontActivity"))
        assertTrue(FreezeReasonClassifier.isToFrontReason("notifyMovetoFront"))
        assertTrue(FreezeReasonClassifier.isToFrontReason("Activity Resume"))
        assertTrue(FreezeReasonClassifier.isToFrontReason("Activity Front"))
        assertFalse(FreezeReasonClassifier.isToFrontReason("PendingNotify"))

        assertTrue(FreezeReasonClassifier.isNetworkWakeReason("PACKET"))
        assertTrue(FreezeReasonClassifier.isNetworkWakeReason("Net Change"))
        assertTrue(FreezeReasonClassifier.isNetworkWakeReason("Signal other3"))
        assertFalse(FreezeReasonClassifier.isNetworkWakeReason("broadcast"))
        assertFalse(FreezeReasonClassifier.isNetworkWakeReason("Broadcast"))

        assertTrue(FreezeReasonClassifier.isSyncBinderThawReason("Sync Binder4"))
        assertTrue(FreezeReasonClassifier.isSyncBinderThawReason("Check Binder"))

        assertTrue(FreezeReasonClassifier.isChargeThawReason("battery charging"))
        assertTrue(FreezeReasonClassifier.isChargeThawReason("CHARGE_CHANGE"))

        assertTrue(FreezeReasonClassifier.isScreenOnThawReason("screen on-thawAll"))
        assertTrue(FreezeReasonClassifier.isScreenOnThawReason("screen_on"))

        assertEquals(WakeCategory.USER_OPEN, FreezeReasonClassifier.classifyWakeCategory(" Activity Start!"))
        assertEquals(WakeCategory.USER_OPEN, FreezeReasonClassifier.classifyWakeCategory("notifyMovetoFront"))
        assertEquals(WakeCategory.LAUNCH_MODE, FreezeReasonClassifier.classifyWakeCategory("LAUNCH_MODE"))
        assertEquals(WakeCategory.NETWORK, FreezeReasonClassifier.classifyWakeCategory("PACKET"))
        assertEquals(WakeCategory.NETWORK, FreezeReasonClassifier.classifyWakeCategory("Signal other3"))
        assertEquals(WakeCategory.BINDER, FreezeReasonClassifier.classifyWakeCategory("Sync Binder4"))
        assertEquals(WakeCategory.BROADCAST, FreezeReasonClassifier.classifyWakeCategory("PendingNotify"))
        assertEquals(WakeCategory.BROADCAST, FreezeReasonClassifier.classifyWakeCategory("broadcast"))
        assertEquals(WakeCategory.BROADCAST, FreezeReasonClassifier.classifyWakeCategory("Broadcast"))
        assertEquals(WakeCategory.SCREEN_ON, FreezeReasonClassifier.classifyWakeCategory("screen on-thawAll"))
        assertEquals(WakeCategory.CHARGING, FreezeReasonClassifier.classifyWakeCategory("battery charging"))
        assertEquals(WakeCategory.OTHER, FreezeReasonClassifier.classifyWakeCategory("IMMOBULUS"))

        // HyperOS 4 snake_case classification parity
        assertTrue(FreezeReasonClassifier.isToFrontReason("activity_start"))
        assertTrue(FreezeReasonClassifier.isToFrontReason("activity_front"))
        assertTrue(FreezeReasonClassifier.isToFrontReason("activity_resume"))
        assertTrue(FreezeReasonClassifier.isToFrontReason("fg_activity"))
        assertTrue(FreezeReasonClassifier.isToFrontReason("multitask"))
        assertFalse(FreezeReasonClassifier.isToFrontReason("freeform_mode"))
        assertFalse(FreezeReasonClassifier.isToFrontReason("smallw"))
        assertFalse(FreezeReasonClassifier.isToFrontReason("focus"))
        assertTrue(FreezeReasonClassifier.isUserOpenReason("freeform_mode"))
        assertTrue(FreezeReasonClassifier.isUserOpenReason("smallw"))
        assertTrue(FreezeReasonClassifier.isUserOpenReason("focus"))
        assertFalse(FreezeReasonClassifier.isToFrontReason("pending_notify"))

        assertTrue(FreezeReasonClassifier.isNetworkWakeReason("packet"))
        assertTrue(FreezeReasonClassifier.isNetworkWakeReason("signal_iso"))
        assertTrue(FreezeReasonClassifier.isNetworkWakeReason("signal_other9"))
        assertTrue(FreezeReasonClassifier.isNetworkWakeReason("network_connect"))
        assertTrue(FreezeReasonClassifier.isSyncBinderThawReason("sync_binder0"))
        assertTrue(FreezeReasonClassifier.isSyncBinderThawReason("check_binder"))

        assertEquals(WakeCategory.USER_OPEN, FreezeReasonClassifier.classifyWakeCategory("activity_front"))
        assertEquals(WakeCategory.USER_OPEN, FreezeReasonClassifier.classifyWakeCategory("multitask"))
        assertEquals(WakeCategory.LAUNCH_MODE, FreezeReasonClassifier.classifyWakeCategory("launch_mode"))
        assertEquals(WakeCategory.NETWORK, FreezeReasonClassifier.classifyWakeCategory("packet"))
        assertEquals(WakeCategory.NETWORK, FreezeReasonClassifier.classifyWakeCategory("signal_other9"))
        assertEquals(WakeCategory.BINDER, FreezeReasonClassifier.classifyWakeCategory("sync_binder0"))
        assertEquals(WakeCategory.BINDER, FreezeReasonClassifier.classifyWakeCategory("fg_important"))
        assertEquals(WakeCategory.BROADCAST, FreezeReasonClassifier.classifyWakeCategory("pending_notify"))
        assertEquals(WakeCategory.SCREEN_ON, FreezeReasonClassifier.classifyWakeCategory("screen_on"))
        assertEquals(WakeCategory.OTHER, FreezeReasonClassifier.classifyWakeCategory("immobulus"))
    }

    @Test
    fun `aggregation classifies wakes tracks screen off frozen duration and respects fallback sourceNote`() {
        var nowMs = millisAt(2026, 11, 17, 10, 0)
        var elapsedMs = 1_000L
        var dispatchActive = true
        val resolvedPkgs = mapOf(10101 to "com.chat.app", 10102 to "com.shop.app")

        val engine = FreezeStatsEngine(
            zoneId = testZone,
            clock = { nowMs },
            elapsedClock = { elapsedMs },
            enabledProvider = { true },
            packageResolver = { uid -> resolvedPkgs[uid] },
            dispatchThawEventActiveProvider = { dispatchActive },
        )

        engine.initScreenState(screenOn = true)
        engine.recordFreezeBatch(listOf(10101, 10101, 10102, 1000), "IMMOBULUS")
        assertTrue(engine.isUidTrackedFrozen(10101))
        assertTrue(engine.isUidTrackedFrozen(10102))

        // 息屏 30 分钟
        nowMs += 10 * 60_000L
        elapsedMs += 10 * 60_000L
        engine.onScreenStateChanged(screenOn = false)

        // 息屏 20 分钟时网络唤醒 10101（总计冻结 30 分钟）
        nowMs += 20 * 60_000L
        elapsedMs += 20 * 60_000L
        engine.recordThaw(10101, "PACKET")
        assertFalse(engine.isUidTrackedFrozen(10101))

        // 再过 10 分钟尝试充电唤醒 10102 被拦截，随后亮屏
        nowMs += 10 * 60_000L
        elapsedMs += 10 * 60_000L
        engine.recordIntercept(10102, InterceptRule.NO_CHARGE_THAW, "battery charging")
        engine.recordProtect(10103, "IMMOBULUS", "com.white.app")
        engine.recordKillBlocked(10102, "ProcessManager#kill")
        engine.onScreenStateChanged(screenOn = true)

        val todaySnap = engine.buildSnapshot(StatsWindow.TODAY)
        assertTrue(todaySnap.enabled)
        assertNull(todaySnap.sourceNote)
        assertEquals(24, todaySnap.buckets.size)
        assertEquals(2, todaySnap.freezeCount) // 10101 去重 + 10102；uid 1000 忽略
        assertEquals(1, todaySnap.wakeByCategory[WakeCategory.NETWORK])
        assertEquals(1, todaySnap.screenOffWakeCount)
        assertEquals(30 * 60_000L, todaySnap.screenOffMillis)
        assertEquals(1, todaySnap.interceptByRule[InterceptRule.NO_CHARGE_THAW])
        assertEquals(1, todaySnap.protectedCount)
        assertEquals(1, todaySnap.killBlockedCount)
        // 10101 冻结 30 分钟，10102 仍处于冻结状态已持续 40 分钟 -> 总计 70 分钟
        assertEquals(70 * 60_000L, todaySnap.frozenMillisTotal)
        assertEquals(1, todaySnap.buckets[10].screenOffWakes)
        assertEquals(0, todaySnap.buckets[10].screenOnWakes)

        // 验证懒解析包名与排序
        val app10101 = todaySnap.apps.first { it.uid == 10101 }
        assertEquals("com.chat.app", app10101.packageName)
        assertEquals(30 * 60_000L, app10101.frozenMillis)
        assertEquals(listOf("PACKET" to 1), app10101.topRawReasons)

        // 验证 fallback sourceNote
        dispatchActive = false
        val bootSnap = engine.buildSnapshot(StatsWindow.BOOT)
        assertEquals(FreezeStatsEngine.SOURCE_NOTE_FALLBACK, bootSnap.sourceNote)
        assertTrue(bootSnap.buckets.isEmpty())
    }

    @Test
    fun `disabled gate skips all allocations and exposes enabled false`() {
        var nowMs = millisAt(2026, 11, 17, 12, 0)
        var enabled = false

        val engine = FreezeStatsEngine(
            zoneId = testZone,
            clock = { nowMs },
            enabledProvider = { enabled },
            packageResolver = { "com.ignored.app" },
        )

        engine.recordFreeze(10101, "IMMOBULUS", "com.ignored.app")
        engine.recordThaw(10101, "PACKET")
        engine.recordIntercept(10101, InterceptRule.BLOCK_THAW, "Alarm")
        engine.recordSystemIntercept(InterceptRule.NO_CHARGE_THAW, "battery charging")
        engine.recordProtect(10101, "IMMOBULUS")
        engine.recordKillBlocked(10101, "ProcessManager#kill")

        val snap = engine.buildSnapshot(StatsWindow.TODAY)
        assertFalse(snap.enabled)
        assertTrue(snap.isEmpty)
        assertTrue(snap.apps.isEmpty())
        assertTrue(snap.recent.isEmpty())

        enabled = true
        nowMs += 60_000L
        engine.recordFreeze(10101, "IMMOBULUS")
        val enabledSnap = engine.buildSnapshot(StatsWindow.TODAY)
        assertTrue(enabledSnap.enabled)
        assertEquals(1, enabledSnap.freezeCount)
    }

    @Test
    fun `ring buffer raw reasons and app count stay bounded`() {
        var nowMs = millisAt(2026, 11, 17, 9, 0)
        val engine = FreezeStatsEngine(
            zoneId = testZone,
            clock = { nowMs },
            enabledProvider = { true },
            packageResolver = { uid -> "com.app.u$uid" },
        )

        // 写入 300 条动态，环形缓冲区最多保留最新 256 条且按倒序排列
        for (i in 0 until 300) {
            nowMs += 1_000L
            engine.recordFreeze(10100 + (i % 10), "r$i")
        }
        val snap = engine.buildSnapshot(StatsWindow.TODAY)
        assertEquals(256, snap.recent.size)
        assertEquals("r299", snap.recent.first().rawReason)
        assertEquals("r44", snap.recent.last().rawReason)

        // 单应用写入 25 种不同理由（其中 2 种高频，23 种低频），最终仅返回 Top 5
        repeat(20) {
            nowMs += 1_000L
            engine.recordFreeze(10100, "warm")
            nowMs += 11_000L
            engine.recordThaw(10100, "PACKET")
        }
        repeat(10) {
            nowMs += 1_000L
            engine.recordFreeze(10100, "warm")
            nowMs += 11_000L
            engine.recordThaw(10100, "Sync Binder4")
        }
        for (k in 1..23) {
            nowMs += 1_000L
            engine.recordFreeze(10100, "warm")
            nowMs += 11_000L
            engine.recordThaw(10100, "rare_reason_$k")
        }
        val afterReasons = engine.buildSnapshot(StatsWindow.TODAY)
        val app = afterReasons.apps.first { it.uid == 10100 }
        assertEquals(5, app.topRawReasons.size)
        assertEquals("PACKET" to 20, app.topRawReasons[0])
        assertEquals("Sync Binder4" to 10, app.topRawReasons[1])

        // 超过 256 个不同 UID 时上限受控
        for (u in 0 until 280) {
            nowMs += 100L
            engine.recordFreeze(10500 + u, "IMMOBULUS")
        }
        val boundedAppsSnap = engine.buildSnapshot(StatsWindow.TODAY)
        assertTrue(boundedAppsSnap.apps.size <= FreezeStatsEngine.MAX_APPS_PER_SCOPE)
    }

    @Test
    fun `day rollover splits ongoing screen off and frozen duration and prunes older than 7 days`() {
        // 第 1 天 23:50 开始冻结与息屏
        var nowMs = millisAt(2026, 11, 10, 23, 50)
        val engine = FreezeStatsEngine(
            zoneId = testZone,
            clock = { nowMs },
            enabledProvider = { true },
            packageResolver = { "com.overnight.app" },
        )

        engine.initScreenState(screenOn = false)
        engine.recordFreeze(10111, "IMMOBULUS")

        // 跨越午夜来到第 2 天 00:20
        nowMs = millisAt(2026, 11, 11, 0, 20)
        engine.recordThaw(10111, "screen on")
        engine.onScreenStateChanged(screenOn = true)

        val day2Today = engine.buildSnapshot(StatsWindow.TODAY)
        assertEquals(0, day2Today.freezeCount)
        assertEquals(1, day2Today.wakeByCategory[WakeCategory.SCREEN_ON])
        assertEquals(1, day2Today.screenOffWakeCount)
        // 00:00..00:20 = 20 分钟归今天
        assertEquals(20 * 60_000L, day2Today.frozenMillisTotal)
        assertEquals(20 * 60_000L, day2Today.screenOffMillis)

        // 近 7 天应同时包含第 1 天的 10 分钟与第 2 天的 20 分钟 = 30 分钟
        val weekSnap = engine.buildSnapshot(StatsWindow.WEEK)
        assertEquals(7, weekSnap.buckets.size)
        assertEquals(1, weekSnap.freezeCount)
        assertEquals(30 * 60_000L, weekSnap.frozenMillisTotal)
        assertEquals(30 * 60_000L, weekSnap.screenOffMillis)

        // 前进 8 天后（11月19日），11月10日/11日的数据应从近 7 天中滚出，但开机以来仍保留
        nowMs = millisAt(2026, 11, 19, 10, 0)
        val prunedWeek = engine.buildSnapshot(StatsWindow.WEEK)
        assertEquals(0, prunedWeek.freezeCount)
        assertEquals(0L, prunedWeek.frozenMillisTotal)

        val bootSnap = engine.buildSnapshot(StatsWindow.BOOT)
        assertEquals(1, bootSnap.freezeCount)
        assertEquals(30 * 60_000L, bootSnap.frozenMillisTotal)
    }

    @Test
    fun `persistence restores same-boot on hot reload resets boot on reboot and survives reset`() {
        val storageFile = File(tempFolder.root, "stats.v1")
        var nowMs = millisAt(2026, 11, 17, 14, 0)
        var elapsedMs = 10_000L
        var bootId = "boot-id-1"

        val engine1 = FreezeStatsEngine(
            storageFile = storageFile,
            zoneId = testZone,
            clock = { nowMs },
            elapsedClock = { elapsedMs },
            bootIdProvider = { bootId },
            enabledProvider = { true },
            packageResolver = { "com.persist.app" },
            asyncIoExecutor = { it() },
        )

        engine1.recordFreeze(10300, "IMMOBULUS", "com.persist.app")
        nowMs += 5 * 60_000L
        elapsedMs += 5 * 60_000L
        engine1.recordIntercept(10300, InterceptRule.IGNORE_NETWORK, "PACKET", "com.persist.app")
        assertTrue(engine1.saveSync())
        assertTrue(storageFile.exists())

        // 1. 同一 bootId 模拟热重载：TODAY、WEEK、BOOT 与 activeFrozenSinceByUid 全部恢复
        nowMs += 5 * 60_000L
        elapsedMs += 5 * 60_000L
        val engineHotReload = FreezeStatsEngine(
            storageFile = storageFile,
            zoneId = testZone,
            clock = { nowMs },
            elapsedClock = { elapsedMs },
            bootIdProvider = { bootId },
            enabledProvider = { true },
            packageResolver = { "com.persist.app" },
            asyncIoExecutor = { it() },
        )
        assertTrue(engineHotReload.restoreFromDisk())
        assertTrue(engineHotReload.isUidTrackedFrozen(10300))

        val hotToday = engineHotReload.buildSnapshot(StatsWindow.TODAY)
        val hotBoot = engineHotReload.buildSnapshot(StatsWindow.BOOT)
        assertEquals(1, hotToday.freezeCount)
        assertEquals(1, hotBoot.freezeCount)
        assertEquals(10 * 60_000L, hotBoot.frozenMillisTotal)
        assertEquals(1, hotBoot.interceptByRule[InterceptRule.IGNORE_NETWORK])
        assertTrue(engineHotReload.saveSync())

        // 2. 更换 bootId 模拟重启：TODAY & WEEK 保留，BOOT 与运行中冻结态清零
        bootId = "boot-id-2"
        elapsedMs = 2_000L
        nowMs += 2 * 60_000L
        val engineReboot = FreezeStatsEngine(
            storageFile = storageFile,
            zoneId = testZone,
            clock = { nowMs },
            elapsedClock = { elapsedMs },
            bootIdProvider = { bootId },
            enabledProvider = { true },
            packageResolver = { "com.persist.app" },
            asyncIoExecutor = { it() },
        )
        assertTrue(engineReboot.restoreFromDisk())
        assertFalse(engineReboot.isUidTrackedFrozen(10300))

        val rebootToday = engineReboot.buildSnapshot(StatsWindow.TODAY)
        val rebootBoot = engineReboot.buildSnapshot(StatsWindow.BOOT)
        assertEquals(1, rebootToday.freezeCount)
        assertEquals(0, rebootBoot.freezeCount)
        assertEquals(0L, rebootBoot.frozenMillisTotal)

        // 3. 重置清空并持久化
        assertTrue(engineReboot.reset())
        val afterReset = FreezeStatsEngine(
            storageFile = storageFile,
            zoneId = testZone,
            clock = { nowMs },
            elapsedClock = { elapsedMs },
            bootIdProvider = { bootId },
            enabledProvider = { true },
        )
        assertTrue(afterReset.restoreFromDisk())
        assertTrue(afterReset.buildSnapshot(StatsWindow.TODAY).isEmpty)
        assertTrue(afterReset.buildSnapshot(StatsWindow.WEEK).isEmpty)
        assertTrue(afterReset.buildSnapshot(StatsWindow.BOOT).isEmpty)
    }

    @Test
    fun `freeze deduplication across S6 S7 S23 prevents double counting and caches pkg lookup`() {
        var nowMs = millisAt(2026, 11, 17, 11, 0)
        val engine = FreezeStatsEngine(
            zoneId = testZone,
            clock = { nowMs },
            enabledProvider = { true },
        )

        // S7 (after) 先记冻结并缓存包名
        engine.recordFreeze(10401, "IMMOBULUS", packageNameHint = "com.dedup.app", onlyIfNotFrozen = true)
        assertEquals("com.dedup.app", engine.getCachedPackageName(10401))
        assertEquals(10401, engine.getCachedUidForPackage("com.dedup.app"))

        // S6 (freezeUids) 与 S23 (freezePids) 接连触发同一已冻结 UID -> 不应重复计数
        engine.recordFreezeBatch(listOf(10401, 10402, 10402), "IMMOBULUS", onlyIfNotFrozen = true)
        engine.recordFreeze(10401, "freezePids", onlyIfNotFrozen = true)
        engine.recordFreeze(10402, "freezePids", onlyIfNotFrozen = true)

        val snap = engine.buildSnapshot(StatsWindow.TODAY)
        assertEquals(2, snap.freezeCount)
        assertEquals(1, snap.apps.first { it.uid == 10401 }.freezeCount)
        assertEquals(1, snap.apps.first { it.uid == 10402 }.freezeCount)

        // 解冻后再次冻结，应正常计为第 2 次冻结
        nowMs += 60_000L
        engine.recordThaw(10401, "PACKET")
        nowMs += 60_000L
        engine.recordFreeze(10401, "IMMOBULUS", onlyIfNotFrozen = true)

        val snapAfterRefreeze = engine.buildSnapshot(StatsWindow.TODAY)
        assertEquals(3, snapAfterRefreeze.freezeCount)
        assertEquals(2, snapAfterRefreeze.apps.first { it.uid == 10401 }.freezeCount)
    }

    @Test
    fun `saveSync flushes ongoing durations even when dirty is false and leaves no tmp files`() {
        val storageFile = File(tempFolder.root, "stats.v1")
        var nowMs = millisAt(2026, 11, 17, 16, 0)
        var elapsedMs = 5_000L

        val engine = FreezeStatsEngine(
            storageFile = storageFile,
            zoneId = testZone,
            clock = { nowMs },
            elapsedClock = { elapsedMs },
            bootIdProvider = { "boot-flush" },
            enabledProvider = { true },
            packageResolver = { "com.flush.app" },
        )

        engine.initScreenState(screenOn = false)
        engine.recordFreeze(10500, "IMMOBULUS", "com.flush.app", onlyIfNotFrozen = true)
        assertTrue(engine.saveIfDirty()) // 保存后 dirty 变为 false

        // 保持冻结与息屏 15 分钟且不再产生新事件（dirty 仍为 false）
        nowMs += 15 * 60_000L
        elapsedMs += 15 * 60_000L
        assertFalse(engine.saveIfDirty()) // 非强制保存跳过

        // saveSync()（对应 onHotReloading）必须强制冲刷进行中的冻结与息屏时长并落盘
        assertTrue(engine.saveSync())
        val leftoverTmps = tempFolder.root.listFiles()?.filter { it.name.endsWith(".tmp") }.orEmpty()
        assertTrue(leftoverTmps.isEmpty())

        val restored = FreezeStatsEngine(
            storageFile = storageFile,
            zoneId = testZone,
            clock = { nowMs },
            elapsedClock = { elapsedMs },
            bootIdProvider = { "boot-flush" },
            enabledProvider = { true },
        )
        assertTrue(restored.restoreFromDisk())
        val snap = restored.buildSnapshot(StatsWindow.TODAY)
        assertEquals(15 * 60_000L, snap.frozenMillisTotal)
        assertEquals(15 * 60_000L, snap.screenOffMillis)
    }

    @Test
    fun `corrupt stats file fails gracefully on restore`() {
        val storageFile = File(tempFolder.root, "stats.v1")
        storageFile.writeText("corrupted binary garbage \u0000\u0001")

        val engine = FreezeStatsEngine(
            storageFile = storageFile,
            zoneId = testZone,
            clock = { millisAt(2026, 11, 17, 15, 0) },
        )
        assertFalse(engine.restoreFromDisk())
        assertTrue(engine.buildSnapshot(StatsWindow.TODAY).isEmpty)
    }

    @Test
    fun `isolated and app zygote UIDs are excluded from recording and purged on restore`() {
        val storageFile = File(tempFolder.root, "stats.v1")
        val nowMs = millisAt(2026, 11, 17, 15, 0)
        val elapsedMs = 20_000L
        val epochDay = java.time.Instant.ofEpochMilli(nowMs).atZone(testZone).toLocalDate().toEpochDay()

        val engine = FreezeStatsEngine(
            storageFile = storageFile,
            zoneId = testZone,
            clock = { nowMs },
            elapsedClock = { elapsedMs },
            bootIdProvider = { "boot-isolated" },
            enabledProvider = { true },
            packageResolver = { uid -> if (uid == 10200) "com.keep.app" else null },
        )

        engine.recordFreezeBatch(listOf(90113, 99016, 10200), "IMMOBULUS")
        engine.recordThaw(90113, "PACKET")
        engine.recordIntercept(99016, InterceptRule.IGNORE_NETWORK, "push")
        engine.recordProtect(99017, "fg")
        engine.recordKillBlocked(90118, "signal")

        assertFalse(engine.isUidTrackedFrozen(90113))
        assertFalse(engine.isUidTrackedFrozen(99016))
        assertTrue(engine.isUidTrackedFrozen(10200))

        val liveSnap = engine.buildSnapshot(StatsWindow.TODAY)
        assertEquals(1, liveSnap.freezeCount)
        assertEquals(0, liveSnap.interceptCount)
        assertEquals(0, liveSnap.protectedCount)
        assertEquals(0, liveSnap.killBlockedCount)
        assertEquals(listOf(10200), liveSnap.apps.map { it.uid })

        // 模拟磁盘上残留旧版记录的 90xxx / 99xxx 隔离 UID：恢复时应自动剔除并保留真实应用与系统拦截事件
        storageFile.writeText(
            """
            v2
            S|${nowMs - 60_000L}|${nowMs - 60_000L}|${nowMs - elapsedMs}|$elapsedMs|$nowMs|boot-isolated
            BT|2|0|0|0|0|5000||
            BA|99013||1|900000|0|0|||
            BA|10200|com.keep.app|1|5000|0|0|||
            D|$epochDay|2|0|0|0|0|5000|||0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0|0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0
            DA|$epochDay|99013||1|900000|0|0|||
            DA|$epochDay|10200|com.keep.app|1|5000|0|0|||
            F|99013|${nowMs - 30_000L}
            F|10200|${nowMs - 10_000L}
            R|${nowMs - 30_000L}|99013||FREEZE|||IMMOBULUS
            R|${nowMs - 20_000L}|1000|android|INTERCEPT||IGNORE_NETWORK|push
            R|${nowMs - 10_000L}|10200|com.keep.app|FREEZE|||IMMOBULUS
            """.trimIndent() + "\n"
        )

        val restored = FreezeStatsEngine(
            storageFile = storageFile,
            zoneId = testZone,
            clock = { nowMs },
            elapsedClock = { elapsedMs },
            bootIdProvider = { "boot-isolated" },
            enabledProvider = { true },
            packageResolver = { uid -> if (uid == 10200) "com.keep.app" else null },
        )
        assertTrue(restored.restoreFromDisk())
        assertFalse(restored.isUidTrackedFrozen(99013))
        assertTrue(restored.isUidTrackedFrozen(10200))

        val restoredToday = restored.buildSnapshot(StatsWindow.TODAY)
        assertEquals(listOf(10200), restoredToday.apps.map { it.uid })
        assertEquals(listOf(10200, 1000), restoredToday.recent.map { it.uid })
        assertTrue(restored.saveIfDirty())
    }

    @Test
    fun `dual app uid does not fall back to another user package`() {
        var nowMs = millisAt(2026, 11, 17, 15, 0)
        val resolvedUids = ArrayList<Int>()
        val engine = FreezeStatsEngine(
            zoneId = testZone,
            clock = { nowMs },
            enabledProvider = { true },
            packageResolver = { uid ->
                resolvedUids.add(uid)
                if (uid == 10296) "com.tencent.mm" else null
            },
        )

        engine.recordFreeze(99910296, "IMMOBULUS")
        val snap = engine.buildSnapshot(StatsWindow.TODAY)
        val app = snap.apps.single()
        assertEquals(99910296, app.uid)
        assertEquals("uid:99910296", app.packageName)
        assertEquals(listOf(99910296), resolvedUids)
    }

    @Test
    fun `unknown frozen uid query preserves tracking while a valid empty result clears it`() {
        var nowMs = millisAt(2026, 11, 17, 15, 0)
        var elapsedMs = 1000L
        var frozenUids: Set<Int>? = setOf(10296)
        val engine = FreezeStatsEngine(
            zoneId = testZone,
            clock = { nowMs },
            elapsedClock = { elapsedMs },
            enabledProvider = { true },
            frozenUidsProvider = { frozenUids },
        )

        engine.recordFreezeBatch(listOf(10296), "IMMOBULUS")
        frozenUids = null
        nowMs += 1000L
        elapsedMs += 1000L
        val unknown = engine.buildSnapshot(StatsWindow.TODAY)
        assertTrue(engine.isUidTrackedFrozen(10296))
        assertEquals(1000L, unknown.frozenMillisTotal)
        engine.recordFreezeBatch(listOf(10296), "IMMOBULUS")
        assertEquals(1, engine.buildSnapshot(StatsWindow.TODAY).freezeCount)

        frozenUids = emptySet()
        nowMs += 1000L
        elapsedMs += 1000L
        engine.buildSnapshot(StatsWindow.TODAY)
        assertFalse(engine.isUidTrackedFrozen(10296))
        engine.recordFreezeBatch(listOf(10296), "IMMOBULUS")
        assertEquals(2, engine.buildSnapshot(StatsWindow.TODAY).freezeCount)
    }

    @Test
    fun `application statistics keep users separate without duplicating process uids`() {
        val nowMs = millisAt(2026, 11, 17, 15, 0)
        val engine = FreezeStatsEngine(
            zoneId = testZone,
            clock = { nowMs },
            enabledProvider = { true },
            packageResolver = { uid ->
                if (uid == 10296 || uid == 99910296) "com.tencent.mm" else null
            },
        )

        engine.recordFreezeBatch(listOf(10296, 10296, 99910296), "IMMOBULUS")
        val snapshot = engine.buildSnapshot(StatsWindow.TODAY)
        assertEquals(2, snapshot.freezeCount)
        assertEquals(setOf(10296, 99910296), snapshot.apps.map { it.uid }.toSet())
        assertTrue(snapshot.apps.all { it.packageName == "com.tencent.mm" && it.freezeCount == 1 })
    }

    @Test
    fun `unknown application ownership does not trust cached hints and is resolved once per snapshot`() {
        var nowMs = millisAt(2026, 11, 17, 15, 0)
        val resolvedUids = ArrayList<Int>()
        val engine = FreezeStatsEngine(
            zoneId = testZone,
            clock = { nowMs },
            enabledProvider = { true },
            packageResolver = { uid -> resolvedUids.add(uid); null },
        )

        engine.recordFreeze(10296, "IMMOBULUS", packageNameHint = "com.guessed.app")
        nowMs += 15_000L
        engine.recordThaw(10296, "PACKET", packageNameHint = "com.guessed.app")
        val snapshot = engine.buildSnapshot(StatsWindow.TODAY)
        assertEquals("uid:10296", snapshot.apps.single().packageName)
        assertTrue(snapshot.recent.all { it.packageName == "uid:10296" })
        assertEquals(1, snapshot.freezeCount)
        assertEquals(1, snapshot.wakeByCategory.values.sum())
        assertEquals(listOf(10296), resolvedUids)
    }

    @Test
    fun `pid freeze attribution rejects recycled or unreadable identities and deduplicates application uid`() {
        val first = FreezeGateHooks.PidIdentity(10296, 100L)
        val sameApplication = FreezeGateHooks.PidIdentity(10296, 200L)
        val initial = mapOf(71 to first, 72 to sameApplication, 73 to first, 74 to first, 75 to first)
        val current = mapOf(
            71 to first,
            72 to sameApplication,
            73 to first.copy(startTicks = 101L),
            74 to first.copy(uid = 10373),
            76 to first,
        )
        val uids = FreezeGateHooks.acceptedFreezeUids(initial, listOf(71, 72, 73, 74, 75, 76)) { current[it] }
        assertEquals(listOf(10296), uids)
        assertTrue(FreezeGateHooks.acceptedFreezeUids(initial, emptyList()) { current[it] }.isEmpty())
    }

    @Test
    fun `pid start identity parsing validates pid and handles parentheses in process name`() {
        val fields = listOf("S") + List(18) { "0" } + "12345"
        val raw = "71 (process with ) bracket) ${fields.joinToString(" ")}"
        assertEquals(12345L, FreezeGateHooks.parsePidStartTicks(71, raw))
        assertNull(FreezeGateHooks.parsePidStartTicks(72, raw))
        assertNull(FreezeGateHooks.parsePidStartTicks(71, "71 (process) S"))
        assertNull(FreezeGateHooks.parsePidStartTicks(71, raw.substringBeforeLast(' ') + " 0"))
    }

    @Test
    fun `cold startup preserves shared history and merges new events once after clock calibration`() {
        val storage = File(tempFolder.root, "clock-stats.v1")
        val savedTime = millisAt(2026, 11, 20, 20)
        val sharedUid = 99910131
        val original = FreezeStatsEngine(storageFile = storage, zoneId = testZone,
            clock = { savedTime }, elapsedClock = { 100_000L }, bootIdProvider = { "old" })
        repeat(3) { original.recordFreeze(sharedUid, "old shared") }
        original.recordFreeze(10296, "old ordinary")
        assertTrue(original.saveSync())
        val originalBytes = storage.readText()

        var now = 20_000L
        var elapsed = 20_000L
        val engine = FreezeStatsEngine(storageFile = storage, zoneId = testZone,
            clock = { now }, elapsedClock = { elapsed }, bootIdProvider = { "new" })
        assertTrue(engine.restoreFromDisk())
        engine.initScreenState(false)
        engine.recordFreeze(sharedUid, "new shared")
        elapsed += 15_000L
        now = millisAt(2026, 7, 30, 10)
        engine.recordThaw(sharedUid, "PACKET")
        engine.recordIntercept(sharedUid, InterceptRule.IGNORE_NETWORK, "PACKET")
        engine.recordProtect(10296, "protected")
        engine.recordKillBlocked(10296, "kill blocked")
        assertFalse(engine.saveSync())
        assertEquals(originalBytes, storage.readText())

        now = savedTime + 60_000L
        engine.recordFreeze(10373, "after calibration")
        val today = engine.buildSnapshot(StatsWindow.TODAY)
        assertEquals(6, today.freezeCount)
        assertEquals(4, today.apps.first { it.uid == sharedUid }.freezeCount)
        assertEquals(1, today.screenOffWakeCount)
        assertEquals(1, today.wakeByCategory[WakeCategory.NETWORK])
        assertEquals(1, today.interceptByRule[InterceptRule.IGNORE_NETWORK])
        assertEquals(1, today.protectedCount)
        assertEquals(1, today.killBlockedCount)
        assertEquals(15_000L, today.frozenMillisTotal)
        assertEquals(15_000L, today.screenOffMillis)
        assertTrue(today.recent.all { it.timeMillis in savedTime..now })
        assertEquals(2, engine.buildSnapshot(StatsWindow.BOOT).freezeCount)
        assertEquals(6, engine.buildSnapshot(StatsWindow.TODAY).freezeCount)
        assertTrue(engine.restoreFromDisk())
        assertEquals(6, engine.buildSnapshot(StatsWindow.TODAY).freezeCount)
        assertTrue(engine.saveSync())

        val nextBoot = FreezeStatsEngine(storageFile = storage, zoneId = testZone,
            clock = { now }, elapsedClock = { 1_000L }, bootIdProvider = { "third" })
        assertTrue(nextBoot.restoreFromDisk())
        assertEquals(4, nextBoot.buildSnapshot(StatsWindow.TODAY).apps.first { it.uid == sharedUid }.freezeCount)
        assertEquals(0, nextBoot.buildSnapshot(StatsWindow.BOOT).freezeCount)
    }

    @Test
    fun `untrusted clock blocks periodic forced and screen triggered saves without touching storage`() {
        val storage = File(tempFolder.root, "protected-stats.v1")
        val savedTime = millisAt(2026, 11, 20, 20)
        val original = FreezeStatsEngine(storageFile = storage, zoneId = testZone, clock = { savedTime })
        original.recordFreeze(99910131, "old")
        assertTrue(original.saveSync())
        val originalBytes = storage.readText()
        var elapsed = 20_000L
        val engine = FreezeStatsEngine(storageFile = storage, zoneId = testZone,
            clock = { millisAt(2026, 7, 30, 10) }, elapsedClock = { elapsed },
            bootIdProvider = { "new" }, asyncIoExecutor = { it() })
        assertTrue(engine.restoreFromDisk())
        engine.recordFreeze(10373, "new")
        engine.onScreenStateChanged(false)
        elapsed += 31L * 60_000L
        assertFalse(engine.saveIfDirty())
        assertFalse(engine.saveSync())
        assertFalse(engine.reset())
        assertEquals(originalBytes, storage.readText())
        assertFalse(tempFolder.root.listFiles().orEmpty().any { it.name.endsWith(".tmp") })
    }

    @Test
    fun `same boot deferred restore retains active deduplication and elapsed duration`() {
        val storage = File(tempFolder.root, "same-boot-clock.v1")
        val savedTime = millisAt(2026, 11, 20, 20)
        val original = FreezeStatsEngine(storageFile = storage, zoneId = testZone,
            clock = { savedTime }, elapsedClock = { 100_000L }, bootIdProvider = { "same" })
        original.recordFreeze(10296, "old", onlyIfNotFrozen = true)
        assertTrue(original.saveSync())
        var now = 20_000L
        var elapsed = 105_000L
        val engine = FreezeStatsEngine(storageFile = storage, zoneId = testZone,
            clock = { now }, elapsedClock = { elapsed }, bootIdProvider = { "same" })
        assertTrue(engine.restoreFromDisk())
        engine.recordFreeze(10296, "duplicate", onlyIfNotFrozen = true)
        elapsed += 15_000L
        now = savedTime + 10_000L
        assertEquals(1, engine.buildSnapshot(StatsWindow.BOOT).freezeCount)
        assertEquals(10_000L, engine.buildSnapshot(StatsWindow.BOOT).frozenMillisTotal)
        assertTrue(engine.isUidTrackedFrozen(10296))
        elapsed += 2_000L
        now += 2_000L
        engine.recordThaw(10296, "PACKET")
        assertFalse(engine.isUidTrackedFrozen(10296))
        assertEquals(12_000L, engine.buildSnapshot(StatsWindow.BOOT).frozenMillisTotal)
    }

    @Test
    fun `first install waits for a valid clock without losing startup events`() {
        val storage = File(tempFolder.root, "first-clock.v1")
        var now = 20_000L
        var elapsed = 20_000L
        val engine = FreezeStatsEngine(storageFile = storage, zoneId = testZone,
            clock = { now }, elapsedClock = { elapsed }, bootIdProvider = { "first" })
        assertFalse(engine.restoreFromDisk())
        engine.initScreenState(false)
        engine.recordFreeze(10296, "before calibration")
        assertFalse(engine.saveSync())
        assertFalse(storage.exists())
        elapsed += 3_000L
        now = millisAt(2026, 11, 20, 21)
        engine.recordThaw(10296, "PACKET")
        val snapshot = engine.buildSnapshot(StatsWindow.TODAY)
        assertEquals(1, snapshot.freezeCount)
        assertEquals(1, snapshot.screenOffWakeCount)
        assertEquals(3_000L, snapshot.frozenMillisTotal)
        assertEquals(3_000L, snapshot.screenOffMillis)
        assertEquals(now - 3_000L, snapshot.recent.first { it.kind == StatsEventKind.FREEZE }.timeMillis)
        assertTrue(engine.saveSync())
        assertTrue(storage.readText().startsWith("v3"))
    }

    @Test
    fun `existing flush merges deferred history when clock becomes valid without a new event`() {
        val storage = File(tempFolder.root, "flush-clock.v1")
        val savedTime = millisAt(2026, 11, 20, 20)
        val original = FreezeStatsEngine(storageFile = storage, zoneId = testZone, clock = { savedTime })
        original.recordFreeze(99910131, "old")
        assertTrue(original.saveSync())
        var now = 20_000L
        var elapsed = 20_000L
        val engine = FreezeStatsEngine(storageFile = storage, zoneId = testZone,
            clock = { now }, elapsedClock = { elapsed }, bootIdProvider = { "new" })
        assertTrue(engine.restoreFromDisk())
        engine.recordFreeze(10373, "new")
        elapsed += 1_000L
        now = savedTime + 60_000L
        assertTrue(engine.saveSync())
        assertEquals(2, engine.buildSnapshot(StatsWindow.TODAY).freezeCount)
        assertEquals(1, engine.buildSnapshot(StatsWindow.BOOT).freezeCount)
    }

    @Test
    fun `calibrated deferred restore still prunes history outside seven day window`() {
        val storage = File(tempFolder.root, "aged-clock.v1")
        val savedTime = millisAt(2026, 11, 10, 20)
        val original = FreezeStatsEngine(storageFile = storage, zoneId = testZone, clock = { savedTime })
        original.recordFreeze(99910131, "expired")
        assertTrue(original.saveSync())
        var now = 20_000L
        var elapsed = 20_000L
        val engine = FreezeStatsEngine(storageFile = storage, zoneId = testZone,
            clock = { now }, elapsedClock = { elapsed }, bootIdProvider = { "new" })
        assertTrue(engine.restoreFromDisk())
        engine.recordFreeze(10373, "new")
        elapsed += 1_000L
        now = millisAt(2026, 11, 20, 21)
        val snapshot = engine.buildSnapshot(StatsWindow.WEEK)
        assertEquals(1, snapshot.freezeCount)
        assertEquals(listOf(10373), snapshot.apps.map { it.uid })
    }

    @Test
    fun `jitter freeze is rolled back and churn wakes are coalesced`() {
        var nowMs = millisAt(2026, 11, 17, 15, 0)
        val engine = FreezeStatsEngine(
            zoneId = testZone,
            clock = { nowMs },
            enabledProvider = { true },
            packageResolver = { "com.churn.app" },
        )
        engine.initScreenState(screenOn = false)
        repeat(5) {
            engine.recordFreeze(10296, "PACKET", onlyIfNotFrozen = true)
            nowMs += 10L
            engine.recordThaw(10296, "PACKET")
            nowMs += 15_000L
        }
        val snap = engine.buildSnapshot(StatsWindow.TODAY)
        assertEquals(0, snap.freezeCount)
        assertEquals(1, snap.wakeByCategory[WakeCategory.NETWORK])
        assertEquals(1, snap.recent.size)

        // 超过合并窗口后，新的一轮抖动唤醒应重新计数。
        nowMs += 3 * 60_000L
        engine.recordFreeze(10296, "PACKET", onlyIfNotFrozen = true)
        nowMs += 10L
        engine.recordThaw(10296, "PACKET")
        assertEquals(2, engine.buildSnapshot(StatsWindow.TODAY).wakeByCategory[WakeCategory.NETWORK])

        engine.recordFreeze(10296, "IMMOBULUS", onlyIfNotFrozen = true)
        nowMs += 60_000L
        engine.recordThaw(10296, "screen on")
        val after = engine.buildSnapshot(StatsWindow.TODAY)
        assertEquals(1, after.freezeCount)
        assertEquals(60_000L, after.apps.single { it.uid == 10296 }.frozenMillis)
    }

    @Test
    fun `doze session records cpu deltas battery drop and idempotent deep entry`() {
        var nowMs = millisAt(2026, 11, 25, 2, 0)
        var elapsedMs = 100_000L
        var cpuMap = mapOf(10101 to 1_000L, 10102 to 500L)
        val engine = FreezeStatsEngine(
            zoneId = testZone,
            clock = { nowMs },
            elapsedClock = { elapsedMs },
            enabledProvider = { true },
            packageResolver = { uid -> if (uid == 10101) "com.chat.app" else "com.news.app" },
        )

        // 进入深睡 IDLE(5)，随后重复上报 IDLE(5)（如 stepIdleStateLocked + runDeepDozeAlarm 同时触发）
        engine.onDozeStateObserved(
            state = 5,
            wallMs = nowMs,
            elapsedMs = elapsedMs,
            batteryPct = 90,
            isPlugged = false,
            cpuSnapshotProvider = { io.github.univers629.millet.core.CpuStatSource.PROC_UID_STAT to cpuMap },
        )
        nowMs += 5_000L
        elapsedMs += 5_000L
        engine.onDozeStateObserved(
            state = 5,
            wallMs = nowMs,
            elapsedMs = elapsedMs,
            batteryPct = 90,
            isPlugged = false,
            cpuSnapshotProvider = { io.github.univers629.millet.core.CpuStatSource.PROC_UID_STAT to cpuMap },
        )

        // 进入维护窗口 IDLE_MAINTENANCE(6)
        nowMs += 600_000L
        elapsedMs += 600_000L
        engine.onDozeStateObserved(
            state = 6,
            wallMs = nowMs,
            elapsedMs = elapsedMs,
            batteryPct = 89,
            isPlugged = false,
            cpuSnapshotProvider = { io.github.univers629.millet.core.CpuStatSource.PROC_UID_STAT to cpuMap },
        )

        // 退出深睡 -> ACTIVE(0)
        nowMs += 600_000L
        elapsedMs += 600_000L
        cpuMap = mapOf(10101 to 2_500L, 10102 to 540L) // 10102 增量 40ms (<100ms 噪声阈值) 被过滤
        engine.onDozeStateObserved(
            state = 0,
            wallMs = nowMs,
            elapsedMs = elapsedMs,
            batteryPct = 88,
            isPlugged = false,
            cpuSnapshotProvider = { io.github.univers629.millet.core.CpuStatSource.PROC_UID_STAT to cpuMap },
        )

        val snap = engine.buildSnapshot(StatsWindow.TODAY)
        assertEquals(1, snap.dozeSummary.sessionCount)
        assertEquals(1_205_000L, snap.dozeSummary.totalDurationMillis)
        assertEquals(1, snap.dozeSummary.maintenanceCount)
        assertEquals(2, snap.dozeSummary.netBatteryDropPct)
        assertEquals(io.github.univers629.millet.core.CpuStatSource.PROC_UID_STAT, snap.dozeSummary.cpuSource)
        assertEquals(1, snap.dozeSummary.apps.size)
        assertEquals(10101, snap.dozeSummary.apps.single().uid)
        assertEquals(1_500L, snap.dozeSummary.apps.single().cpuMillis)
    }

    @Test
    fun `backfillBootDozeIntervals imports non-overlapping history and respects reset`() {
        var nowMs = millisAt(2026, 11, 25, 19, 0)
        var elapsedMs = 2_000_000L
        val engine = FreezeStatsEngine(
            zoneId = testZone,
            clock = { nowMs },
            elapsedClock = { elapsedMs },
            enabledProvider = { true },
        )

        // 已有一次实时记录 [1_000_000, 1_120_000]
        engine.onDozeStateObserved(
            state = 5,
            wallMs = nowMs - 1_000_000L,
            elapsedMs = 1_000_000L,
            batteryPct = 80,
            isPlugged = false,
            cpuSnapshotProvider = { io.github.univers629.millet.core.CpuStatSource.NONE to emptyMap() },
        )
        engine.onDozeStateObserved(
            state = 0,
            wallMs = nowMs - 880_000L,
            elapsedMs = 1_120_000L,
            batteryPct = 80,
            isPlugged = false,
            cpuSnapshotProvider = { io.github.univers629.millet.core.CpuStatSource.NONE to emptyMap() },
        )

        val intervals = listOf(
            FreezeStatsEngine.BootDozeInterval(400_000L, 628_000L, maintenanceCount = 1), // 历史 228s，应补齐
            FreezeStatsEngine.BootDozeInterval(1_000_000L, 1_120_000L, maintenanceCount = 0), // 与实时重叠，应跳过
            FreezeStatsEngine.BootDozeInterval(1_300_000L, 1_310_000L, maintenanceCount = 0), // <30s，应忽略
        )
        val added = engine.backfillBootDozeIntervals(intervals, nowWallMs = nowMs, nowElapsedMs = elapsedMs)
        assertEquals(1, added)

        val snap = engine.buildSnapshot(StatsWindow.TODAY)
        assertEquals(2, snap.dozeSummary.sessionCount)
        assertEquals(120_000L + 228_000L, snap.dozeSummary.totalDurationMillis)
        assertEquals(1, snap.dozeSummary.maintenanceCount)

        // 重置后不应再把重置前的开机历史重新补回来
        assertTrue(engine.reset())
        nowMs += 10_000L
        elapsedMs += 10_000L
        assertEquals(0, engine.backfillBootDozeIntervals(intervals, nowWallMs = nowMs, nowElapsedMs = elapsedMs))
        assertEquals(0, engine.buildSnapshot(StatsWindow.TODAY).dozeSummary.sessionCount)
    }

    @Test
    fun `doze session exit resolves and persists package names for never-frozen top uids`() {
        val storage = File(tempFolder.root, "doze-top-pkg.v1")
        var nowMs = millisAt(2026, 11, 27, 3, 0)
        var elapsedMs = 200_000L
        var cpuMap = mapOf(10335 to 100L)
        val engine = FreezeStatsEngine(
            storageFile = storage,
            zoneId = testZone,
            clock = { nowMs },
            elapsedClock = { elapsedMs },
            enabledProvider = { true },
            packageResolver = { uid -> if (uid == 10335) "com.ss.android.ugc.trill" else null },
        )

        engine.onDozeStateObserved(
            state = 5,
            wallMs = nowMs,
            elapsedMs = elapsedMs,
            batteryPct = 80,
            isPlugged = false,
            cpuSnapshotProvider = { io.github.univers629.millet.core.CpuStatSource.PROC_UID_STAT to cpuMap },
        )
        nowMs += 90_000L
        elapsedMs += 90_000L
        cpuMap = mapOf(10335 to 2_100L)
        engine.onDozeStateObserved(
            state = 0,
            wallMs = nowMs,
            elapsedMs = elapsedMs,
            batteryPct = 80,
            isPlugged = false,
            cpuSnapshotProvider = { io.github.univers629.millet.core.CpuStatSource.PROC_UID_STAT to cpuMap },
        )

        assertTrue(storage.exists())
        val persisted = storage.readText()
        assertTrue(persisted.contains("10335,com.ss.android.ugc.trill,2000,1"))
    }
}
