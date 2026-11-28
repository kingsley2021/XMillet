package io.github.univers629.millet.system

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Binder
import android.os.PatternMatcher
import io.github.libxposed.api.XposedInterface
import io.github.univers629.millet.core.AppUidUtils
import io.github.univers629.millet.core.Logger
import io.github.univers629.millet.core.ModernBridge
import io.github.univers629.millet.core.ModernHookInstaller
import io.github.univers629.millet.core.NoRestrictSync
import io.github.univers629.millet.core.PrefsKeys
import java.util.concurrent.atomic.AtomicBoolean

/**
 * system_server 侧模块残留清理与卸载自清理协调器。
 *
 * - 通过精确包名过滤的 `ACTION_PACKAGE_FULLY_REMOVED` / `ACTION_PACKAGE_REMOVED`（排除覆盖升级与副用户）
 *   实现事件驱动卸载清理，卸载其他应用不触发回调。
 * - 同时承接 App 侧「清理系统残留（卸载前）」事务，按需释放强制 Doze、回收旧版系统键、
 *   还原模块修改的系统省电策略并删除 `/data/system/XMillet/`。
 */
object ModuleCleanupManager {

    data class CleanupReport(
        val ok: Boolean,
        val revertedBatteryCount: Int,
        val removedNoRestrictCount: Int,
        val deletedFilesCount: Int,
        val dozeReleased: Boolean,
    )

    private val receiverLock = Any()
    private val uninstallTriggered = AtomicBoolean(false)
    private val legacyMigrationCompleted = AtomicBoolean(false)
    private val legacyMigrationInFlight = AtomicBoolean(false)

    @Volatile
    private var uninstallReceiver: BroadcastReceiver? = null

    @Volatile
    private var registeredContext: Context? = null

    /**
     * 判断一条包卸载广播是否属于本模块主用户的真实卸载（排除覆盖升级与多用户/分身空间卸载）。
     */
    internal fun shouldTriggerUninstallCleanup(
        action: String?,
        packageName: String?,
        replacing: Boolean,
        uid: Int = -1,
    ): Boolean {
        if (packageName != PrefsKeys.MODULE_PACKAGE) return false
        if (uid >= 0 && AppUidUtils.userIdOf(uid) != AppUidUtils.PRIMARY_USER_ID) return false
        return when (action) {
            Intent.ACTION_PACKAGE_FULLY_REMOVED -> true
            Intent.ACTION_PACKAGE_REMOVED -> !replacing
            else -> false
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    fun registerUninstallReceiver(context: Context) {
        synchronized(receiverLock) {
            if (uninstallReceiver != null) return
            val targetCtx = context.applicationContext ?: context
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addDataScheme("package")
                addDataSchemeSpecificPart(PrefsKeys.MODULE_PACKAGE, PatternMatcher.PATTERN_LITERAL)
            }
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    val action = intent?.action
                    val pkg = intent?.data?.schemeSpecificPart
                    val replacing = intent?.getBooleanExtra(Intent.EXTRA_REPLACING, false) == true
                    val uid = intent?.getIntExtra(Intent.EXTRA_UID, -1) ?: -1
                    if (!shouldTriggerUninstallCleanup(action, pkg, replacing, uid)) return
                    if (!uninstallTriggered.compareAndSet(false, true)) return
                    val cleanupCtx = ctx ?: targetCtx
                    Thread(
                        {
                            performUninstallSelfCleanup(cleanupCtx)
                        },
                        "XMillet-UninstallCleanup",
                    ).start()
                }
            }
            runCatching {
                targetCtx.registerReceiver(receiver, filter)
                uninstallReceiver = receiver
                registeredContext = targetCtx
            }.onFailure {
                Logger.alwaysWarn("注册卸载自清理广播失败: ${it.javaClass.simpleName}")
            }
        }
    }

    fun unregisterUninstallReceiver() {
        synchronized(receiverLock) {
            val receiver = uninstallReceiver ?: return
            val ctx = registeredContext
            uninstallReceiver = null
            registeredContext = null
            if (ctx != null) {
                runCatching { ctx.unregisterReceiver(receiver) }
            }
        }
    }

    /**
     * 在既有 `XMillet-LogIo` 单线程上调度一次性旧版记账键迁移（不额外起线程）。
     *
     * 若开机早期 PowerKeeper ContentProvider 尚未就绪导致延后，后续 PowerKeeper 建立连接时会再触发一次；
     * 一旦确认无旧键或完成迁移，后续调用直接短路返回。
     */
    fun scheduleLegacyMigration(context: Context?) {
        if (legacyMigrationCompleted.get()) return
        val targetCtx = context ?: SystemState.systemContext ?: return
        if (!legacyMigrationInFlight.compareAndSet(false, true)) return
        Logger.postIoTask {
            try {
                val result = reclaimLegacyNoRestrict(targetCtx)
                if (!result.deferred && (!result.hadLegacyKey || result.keyDeleted)) {
                    legacyMigrationCompleted.set(true)
                }
            } finally {
                legacyMigrationInFlight.set(false)
            }
        }
    }

    /**
     * 二次核验主用户（user 0）是否仍存在本模块安装包，防止异常广播或分身卸载误停用主模块。
     */
    private fun isModuleStillInstalledForPrimaryUser(context: Context): Boolean {
        val token = Binder.clearCallingIdentity()
        return try {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(PrefsKeys.MODULE_PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (_: Throwable) {
            // PackageManager 异常时不误判为已卸载，避免意外停用运行中的模块
            true
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    /**
     * 模块被真实卸载后立即在 system_server 内执行无痕收尾：
     * 核验主用户确已卸载 → 暂停统计与日志落盘 → 停用运行期组件与全部 Hook 句柄 →
     * 回收旧版系统键并删除 `/data/system/XMillet/` → 按 API 102 规范 `detach()` 模块入口。
     */
    private fun performUninstallSelfCleanup(context: Context) {
        if (isModuleStillInstalledForPrimaryUser(context)) {
            uninstallTriggered.set(false)
            Logger.alwaysWarn("忽略卸载清理：主用户仍存在 ${PrefsKeys.MODULE_PACKAGE}")
            return
        }
        runCatching {
            SystemState.isGreezeReady = false
            FreezeStatsRecorder.pausePersistenceForCleanup()
            SystemHooks.onHotReloading()
        }
        runCatching {
            ModernHookInstaller.unhookAll()
        }
        runCatching {
            reclaimLegacyNoRestrict(context)
        }
        runCatching {
            Logger.suspendLocalWriteAndDeleteDir(pauseLocalWrite = true, permanentDisable = true)
        }
        runCatching {
            val module = ModernBridge.xposedModule
            ModernBridge.xposedModule = null
            if (module != null && module.apiVersion >= XposedInterface.API_102) {
                module.detach()
            }
        }
    }

    /**
     * 供 App 侧「清理系统残留（卸载前）」Binder 事务调用的按需清理入口。
     *
     * @param pausePersistence true 表示用户即将卸载（暂停日志与统计落盘，避免卸载前再次建目录）；
     *                         false 表示仅执行清理并继续使用（清盘后允许后续运行正常记录）。
     */
    fun performManualCleanup(
        context: Context?,
        batteryReverts: Map<String, Boolean>,
        deleteDataDir: Boolean,
        pausePersistence: Boolean = false,
    ): CleanupReport {
        val targetCtx = context ?: SystemState.systemContext
        val dozeReleased = runCatching { BroadcastAndDozeHooks.releaseDozeForCleanup() }.getOrDefault(false)
        val reclaim = reclaimLegacyNoRestrict(targetCtx)
        if (!reclaim.deferred && (!reclaim.hadLegacyKey || reclaim.keyDeleted)) {
            legacyMigrationCompleted.set(true)
        }
        var revertedBattery = 0
        for ((pkg, targetNoRestrict) in batteryReverts) {
            if (!AppUidUtils.validPolicyTarget(pkg) || '#' in pkg) continue
            if (runCatching { BatteryPolicyApplier.apply(pkg, targetNoRestrict) }.getOrDefault(false)) {
                revertedBattery++
            }
        }
        runCatching {
            if (pausePersistence) {
                FreezeStatsRecorder.pausePersistenceForCleanup()
            } else {
                FreezeStatsRecorder.resumePersistenceAfterCleanup()
            }
            FreezeStatsRecorder.reset(persist = false)
        }
        val deletedFiles = if (deleteDataDir) {
            runCatching {
                Logger.suspendLocalWriteAndDeleteDir(
                    pauseLocalWrite = pausePersistence,
                    permanentDisable = false,
                )
            }.getOrDefault(0)
        } else {
            0
        }
        return CleanupReport(
            ok = true,
            revertedBatteryCount = revertedBattery,
            removedNoRestrictCount = reclaim.removedCount,
            deletedFilesCount = deletedFiles,
            dozeReleased = dozeReleased,
        )
    }

    /**
     * 回收旧版 `NoRestrictSync` 残留键，保留 PowerKeeper 配置库中明确设为「无限制」的应用。
     * PowerKeeper 配置库尚未就绪时返回 `null` 使 `NoRestrictSync.reclaimLegacy` 安全延后。
     */
    fun reclaimLegacyNoRestrict(context: Context?): NoRestrictSync.ReclaimResult =
        NoRestrictSync.reclaimLegacy(context) { candidates ->
            BatteryPolicyApplier.readBgControlsOrNull(candidates)
                ?.filterValues { it == BatteryPolicyApplier.BG_NO_RESTRICT }
                ?.keys
        }
}
