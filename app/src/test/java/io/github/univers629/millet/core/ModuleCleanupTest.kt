package io.github.univers629.millet.core

import android.content.Intent
import io.github.univers629.millet.system.ModuleCleanupManager
import io.github.univers629.millet.ui.util.ModuleCleanupHelper
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModuleCleanupTest {

    @Test
    fun noRestrictReclaim_removesLegacyEntriesAndPreservesSystemAndExplicitNoRestrict() {
        val current = linkedSetOf(
            "com.miui.home",
            "com.tencent.mm",
            "com.example.user_kept",
            "com.example.module_added",
        )
        val writtenByLegacy = setOf(
            "com.example.module_added",
            "com.example.user_kept",
            "com.example.dual#999",
        )
        val explicitInPowerKeeper = setOf("com.example.user_kept")

        val next = NoRestrictSync.resolveReclaim(
            current = current,
            writtenByUs = writtenByLegacy,
            keepExplicitNoRestrict = explicitInPowerKeeper,
        )

        assertEquals(
            linkedSetOf("com.miui.home", "com.tencent.mm", "com.example.user_kept"),
            next,
        )
    }

    @Test
    fun uninstallBroadcastTrigger_acceptsTrueUninstallAndRejectsUpgradeOrOtherPackage() {
        assertTrue(
            ModuleCleanupManager.shouldTriggerUninstallCleanup(
                action = Intent.ACTION_PACKAGE_FULLY_REMOVED,
                packageName = PrefsKeys.MODULE_PACKAGE,
                replacing = false,
            )
        )
        assertTrue(
            ModuleCleanupManager.shouldTriggerUninstallCleanup(
                action = Intent.ACTION_PACKAGE_REMOVED,
                packageName = PrefsKeys.MODULE_PACKAGE,
                replacing = false,
            )
        )
        assertFalse(
            ModuleCleanupManager.shouldTriggerUninstallCleanup(
                action = Intent.ACTION_PACKAGE_REMOVED,
                packageName = PrefsKeys.MODULE_PACKAGE,
                replacing = true,
            )
        )
        assertFalse(
            ModuleCleanupManager.shouldTriggerUninstallCleanup(
                action = Intent.ACTION_PACKAGE_FULLY_REMOVED,
                packageName = "com.example.other",
                replacing = false,
            )
        )
        assertFalse(
            ModuleCleanupManager.shouldTriggerUninstallCleanup(
                action = Intent.ACTION_PACKAGE_ADDED,
                packageName = PrefsKeys.MODULE_PACKAGE,
                replacing = false,
            )
        )
    }

    @Test
    fun batteryPolicyJournal_tracksFirstOriginAndDropsEntryWhenRestored() {
        val step1 = BatteryPolicyJournal.recordChange(
            current = emptyMap(),
            packageName = "com.example.app",
            originalNoRestrict = false,
            newNoRestrict = true,
        )
        assertEquals(mapOf("com.example.app" to false), step1)

        // 重复设置为无限制不覆盖首次原始状态
        val step2 = BatteryPolicyJournal.recordChange(
            current = step1,
            packageName = "com.example.app",
            originalNoRestrict = true,
            newNoRestrict = true,
        )
        assertEquals(mapOf("com.example.app" to false), step2)

        // 切回原始状态后净改动归零，自动从账本移除
        val step3 = BatteryPolicyJournal.recordChange(
            current = step2,
            packageName = "com.example.app",
            originalNoRestrict = true,
            newNoRestrict = false,
        )
        assertTrue(step3.isEmpty())

        // 无变化的写入不记账
        val noop = BatteryPolicyJournal.recordChange(
            current = emptyMap(),
            packageName = "com.example.same",
            originalNoRestrict = true,
            newNoRestrict = true,
        )
        assertTrue(noop.isEmpty())
    }

    @Test
    fun batteryPolicyJournal_encodeDecodeRoundTripIgnoresMalformedTokens() {
        val raw = BatteryPolicyJournal.encode(
            linkedMapOf(
                "com.example.a" to false,
                "com.example.b" to true,
                "com.example.dual#999" to false,
                "bad=pkg" to true,
            )
        )
        assertEquals("com.example.a=0;com.example.b=1", raw)

        val decoded = BatteryPolicyJournal.decode("$raw;broken;=1;com.example.c=2;com.example.d=1")
        assertEquals(
            linkedMapOf(
                "com.example.a" to false,
                "com.example.b" to true,
                "com.example.d" to true,
            ),
            decoded,
        )
    }

    @Test
    fun loggerDeleteLocalDirRecursively_removesNestedFilesAndDirectory() {
        val dir = Files.createTempDirectory("xmillet-cleanup-test").toFile()
        val sub = File(dir, "nested").apply { mkdirs() }
        File(dir, "log").writeText("line1")
        File(dir, "stats.v1").writeText("data")
        File(sub, "extra").writeText("x")

        val deleted = Logger.deleteLocalDirRecursively(dir)

        assertEquals(3, deleted)
        assertFalse(dir.exists())
    }

    @Test
    fun noRestrictReclaimOrDefer_defersWhenPowerKeeperNotReady() {
        val current = linkedSetOf("com.miui.home", "com.example.user_kept")
        val written = setOf("com.example.user_kept")

        assertNull(NoRestrictSync.resolveReclaimOrDefer(current, written, keepExplicitNoRestrict = null))
        assertEquals(
            linkedSetOf("com.miui.home", "com.example.user_kept"),
            NoRestrictSync.resolveReclaimOrDefer(current, written, keepExplicitNoRestrict = setOf("com.example.user_kept")),
        )
    }

    @Test
    fun uninstallBroadcastTrigger_rejectsSecondaryUsersAndDualSpace() {
        assertTrue(
            ModuleCleanupManager.shouldTriggerUninstallCleanup(
                action = Intent.ACTION_PACKAGE_FULLY_REMOVED,
                packageName = PrefsKeys.MODULE_PACKAGE,
                replacing = false,
                uid = 10234,
            )
        )
        assertFalse(
            ModuleCleanupManager.shouldTriggerUninstallCleanup(
                action = Intent.ACTION_PACKAGE_FULLY_REMOVED,
                packageName = PrefsKeys.MODULE_PACKAGE,
                replacing = false,
                uid = 99910234,
            )
        )
        assertFalse(
            ModuleCleanupManager.shouldTriggerUninstallCleanup(
                action = Intent.ACTION_PACKAGE_REMOVED,
                packageName = PrefsKeys.MODULE_PACKAGE,
                replacing = false,
                uid = 1010234,
            )
        )
    }

    @Test
    fun rootFallbackHelpers_quoteShellSafelyAndParsePowerKeeperRows() {
        assertEquals(
            """'a, '\''$(rm -rf /)'\''`id`'""",
            ModuleCleanupHelper.shellSingleQuote("a, '$(rm -rf /)'`id`"),
        )

        val sample = """
            Row: 0 pkgName=com.tencent.mm, bgControl=noRestrict
            Row: 1 pkgName=com.example.auto, bgControl=miuiAuto
            Row: 2 pkgName=com.example.keep, bgControl=noRestrict
        """.trimIndent()
        assertEquals(
            setOf("com.tencent.mm", "com.example.keep"),
            ModuleCleanupHelper.parsePowerKeeperNoRestrictPackages(sample),
        )
    }
}
