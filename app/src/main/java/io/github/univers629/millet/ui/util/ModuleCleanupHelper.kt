package io.github.univers629.millet.ui.util

import android.content.Context
import android.provider.Settings
import io.github.univers629.millet.core.BatteryPolicyJournal
import io.github.univers629.millet.core.Logger
import io.github.univers629.millet.core.NoRestrictSync
import io.github.univers629.millet.core.RootHelper
import io.github.univers629.millet.core.SystemBatteryOptimizationHelper
import io.github.univers629.millet.ipc.PowerKeeperBinderClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * App 侧「清理系统残留（卸载前）」执行器。
 *
 * 优先走 `system_server` Binder 通道（免 Root）；若模块已禁用或未连接，仅在用户主动点击时
 * 尝试 Root 兜底清理 `/data/system/XMillet/`、旧版系统记账键、强制 Doze 与省电策略账本。
 */
object ModuleCleanupHelper {

    data class CleanupUiResult(
        val success: Boolean,
        val summary: String,
    )

    suspend fun runCleanup(
        context: Context,
        revertBatteryPolicies: Boolean,
        pausePersistence: Boolean = false,
    ): CleanupUiResult = withContext(Dispatchers.IO) {
        val journal = if (revertBatteryPolicies) BatteryPolicyJournal.readAll(context) else emptyMap()

        val viaModule = PowerKeeperBinderClient.cleanupResidue(
            batteryReverts = journal,
            deleteDataDir = true,
            pausePersistence = pausePersistence,
        )
        if (viaModule != null && viaModule.ok) {
            if (revertBatteryPolicies && journal.isNotEmpty()) {
                BatteryPolicyJournal.clear(context)
            }
            val details = buildList {
                add("清理系统文件 ${viaModule.deletedFilesCount} 项")
                if (viaModule.removedNoRestrictCount > 0) {
                    add("回收旧版免冻键 ${viaModule.removedNoRestrictCount} 项")
                }
                if (viaModule.revertedBatteryCount > 0) {
                    add("还原省电策略 ${viaModule.revertedBatteryCount} 项")
                }
            }
            return@withContext CleanupUiResult(
                success = true,
                summary = "清理完成（${details.joinToString("，")}）",
            )
        }

        // 模块未连接时退回 Root 兜底（兜底「先禁用 → 重启 → 再卸载」场景）
        if (!RootHelper.isRootGranted) {
            RootHelper.requestRoot()
        }
        if (!RootHelper.isRootGranted) {
            return@withContext CleanupUiResult(
                success = false,
                summary = "模块未激活且无 Root 权限，请先激活模块或授予 Root 后再清理",
            )
        }

        var revertedCount = 0
        if (revertBatteryPolicies && journal.isNotEmpty()) {
            val revertedPkgs = ArrayList<String>()
            for ((pkg, origNoRestrict) in journal) {
                val ok = if (origNoRestrict) {
                    SystemBatteryOptimizationHelper.setAppIgnoringBatteryOptimization(
                        context = context,
                        packageName = pkg,
                        recordJournal = false,
                    )
                } else {
                    SystemBatteryOptimizationHelper.restoreAppBatteryOptimizationToDefault(
                        context = context,
                        packageName = pkg,
                        recordJournal = false,
                    )
                }
                if (ok) {
                    revertedCount++
                    revertedPkgs.add(pkg)
                }
            }
            BatteryPolicyJournal.remove(context, revertedPkgs)
        }

        val removedLegacy = reclaimLegacyViaRoot(context)
        val script = buildString {
            appendLine("cmd deviceidle unforce 2>/dev/null || true")
            appendLine("rm -rf ${Logger.LOCAL_LOG_DIR}")
            appendLine("settings delete system ${NoRestrictSync.TRACKING_KEY} 2>/dev/null || true")
        }
        val execOk = runCatching {
            RootHelper.execSu(script)
            true
        }.getOrDefault(false)

        if (!execOk) {
            return@withContext CleanupUiResult(
                success = false,
                summary = "Root 清理执行失败，请检查授权状态",
            )
        }
        val parts = buildList {
            add("已清除系统残留目录与强制 Doze")
            if (removedLegacy > 0) add("回收旧版免冻键 $removedLegacy 项")
            if (revertedCount > 0) add("还原省电策略 $revertedCount 项")
        }
        CleanupUiResult(success = true, summary = parts.joinToString("，"))
    }

    internal fun shellSingleQuote(raw: String): String =
        "'${raw.replace("'", "'\\''")}'"

    internal fun parsePowerKeeperNoRestrictPackages(output: String): Set<String> {
        if (output.isBlank()) return emptySet()
        val result = LinkedHashSet<String>()
        val pkgRegex = Regex("""(?:^|[,\s])pkgName=([^,\s]+)""")
        for (line in output.lineSequence()) {
            if (!line.contains("bgControl=noRestrict")) continue
            val match = pkgRegex.find(line) ?: continue
            val pkg = match.groupValues[1].trim()
            if (pkg.isNotEmpty()) result.add(pkg)
        }
        return result
    }

    private fun reclaimLegacyViaRoot(context: Context): Int {
        return runCatching {
            val cr = context.contentResolver
            val rawTracked = Settings.System.getString(cr, NoRestrictSync.TRACKING_KEY) ?: return@runCatching 0
            val written = NoRestrictSync.split(rawTracked)
            if (written.isEmpty()) return@runCatching 0
            val current = NoRestrictSync.split(Settings.System.getString(cr, NoRestrictSync.SYSTEM_KEY))
            val keepExplicit = runCatching {
                val queryOut = RootHelper.execSu(
                    "content query --uri content://com.miui.powerkeeper.configure/userTable " +
                        "--projection pkgName:bgControl --where \"bgControl='noRestrict'\""
                )
                parsePowerKeeperNoRestrictPackages(queryOut.joinToString("\n"))
            }.getOrNull() ?: emptySet()
            val next = NoRestrictSync.resolveReclaim(current, written, keepExplicit)
            val removed = current.size - next.size
            if (next != current) {
                val quoted = shellSingleQuote(next.joinToString(", "))
                RootHelper.execSu("settings put system ${NoRestrictSync.SYSTEM_KEY} $quoted")
            }
            removed
        }.getOrDefault(0)
    }
}
