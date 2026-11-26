package io.github.univers629.millet.system

import android.app.AppOpsManager
import android.content.ContentValues
import android.net.Uri
import android.os.Binder
import android.provider.Settings
import io.github.univers629.millet.core.SETTING_MILLET_NO_RESTRICT_APP
import io.github.univers629.millet.core.AppUidUtils
import io.github.univers629.millet.core.Logger
import io.github.univers629.millet.core.XReflect

/**
 * 在 system_server 内应用「省电策略无限制 / 恢复默认」。
 *
 * 与 App 侧拿 Root 跑 `dumpsys deviceidle whitelist`、`cmd appops set`、`content update` 不同，
 * 模块已经在系统进程里，可以直接调用这几个入口的内部方法，因此不需要 Root：
 *
 * 1. `DeviceIdleController#addPowerSaveWhitelistAppsInternal` / `removePowerSaveWhitelistAppInternal`
 *    —— Doze 省电白名单（系统设置里「无限制」与「优化」的底层状态）。
 * 2. `AppOpsManager#setModeInternal`（Android 13 及更早）/ `AppOpsManager#setMode`（Android 14+）
 *    的 `RUN_ANY_IN_BACKGROUND` —— 后台运行权限，与用户在设置里切换省电策略时系统做的一致。
 * 3. PowerKeeper `user_configure.db` 的 `userTable.bgControl` —— 系统设置界面的真值来源；
 *    不写它的话，PowerKeeper 重建 `MILLET_NO_RESTRICT_APP` 时会冲掉本模块的改动。
 * 4. `Settings.System.MILLET_NO_RESTRICT_APP` —— PowerKeeper 投影给 Greezer 的无限制名单，
 *    墓碑是否绕过冻结只认它。
 *
 * 全部写操作都包在 `Binder.clearCallingIdentity()` 里：入口可能跑在 App 的 binder 线程上，
 * 不清身份会在 AMS / 设置提供者里按调用方 uid 做权限判断。
 */
object BatteryPolicyApplier {

    private const val OP_RUN_ANY_IN_BACKGROUND = "android:run_any_in_background"

    private const val PK_USER_TABLE_URI = "content://com.miui.powerkeeper.configure/userTable"
    private const val COL_BG_CONTROL = "bgControl"
    private const val COL_PKG_NAME = "pkgName"
    private const val COL_USER_ID = "userId"
    internal const val BG_NO_RESTRICT = "noRestrict"
    private const val BG_DEFAULT = "miuiAuto"

    /**
     * 读取 PowerKeeper `userTable.bgControl` 的当前值（包名 → bgControl）。
     *
     * 系统设置「省电策略」显示的就是这一列，用来判断用户是否显式选了「无限制」：
     * P7/P8 拦截 PowerKeeper 自动增删白名单时，如果这一列显示用户想要的方向，
     * 就应当放行，否则会出现「设置成无限制 → 白名单没同步 → 下次进页面又被改回智能推荐」。
     */
    internal fun readBgControls(packages: Collection<String>): Map<String, String> {
        if (packages.isEmpty()) return emptyMap()
        val context = SystemState.systemContext ?: return emptyMap()
        val token = Binder.clearCallingIdentity()
        return try {
            val placeholders = packages.joinToString(",") { "?" }
            val result = HashMap<String, String>(packages.size)
            context.contentResolver.query(
                Uri.parse(PK_USER_TABLE_URI),
                arrayOf(COL_PKG_NAME, COL_BG_CONTROL),
                "$COL_PKG_NAME IN ($placeholders)",
                packages.toTypedArray(),
                null
            )?.use { cursor ->
                val pkgIndex = cursor.getColumnIndex(COL_PKG_NAME)
                val bgIndex = cursor.getColumnIndex(COL_BG_CONTROL)
                if (pkgIndex >= 0 && bgIndex >= 0) {
                    while (cursor.moveToNext()) {
                        val pkg = cursor.getString(pkgIndex)
                        val bg = cursor.getString(bgIndex)
                        if (pkg != null && bg != null) result[pkg] = bg
                    }
                }
            }
            result
        } catch (t: Throwable) {
            Logger.warn("读取 PowerKeeper 配置库失败: ${t.javaClass.simpleName}: ${t.message}")
            emptyMap()
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    /**
     * @param noRestrict true = 设为无限制；false = 恢复系统默认（重新纳入电池优化）
     * @return true 表示至少有一条策略写成功
     */
    fun apply(packageName: String, noRestrict: Boolean): Boolean {
        if (!AppUidUtils.validPolicyTarget(packageName) || '#' in packageName) return false
        val token = Binder.clearCallingIdentity()
        return try {
            val idle = applyDeviceIdle(packageName, noRestrict)
            val ops = applyAppOps(packageName, noRestrict)
            val table = applyPowerKeeperTable(packageName, noRestrict)
            val setting = applyNoRestrictSetting(packageName, noRestrict)
            Logger.alwaysInfo(
                "电池策略[${if (noRestrict) "无限制" else "恢复默认"}] $packageName: " +
                    "doze=$idle appops=$ops 配置库=$table 系统名单=$setting"
            )
            idle || ops || table || setting
        } catch (t: Throwable) {
            Logger.error("应用电池策略失败[$packageName]", t)
            false
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    private fun applyDeviceIdle(packageName: String, noRestrict: Boolean): Boolean {
        val controller = SystemState.deviceIdleController ?: return false
        val written = runCatching {
            if (noRestrict) addToPowerWhitelist(controller, packageName)
            else removeFromPowerWhitelist(controller, packageName)
        }.getOrElse {
            Logger.warn("Doze 白名单写入失败[$packageName]: ${it.javaClass.simpleName}: ${it.message}")
            false
        }
        // 各版本的方法名与返回值都不一样（有的返回 int 数量、有的返回 boolean、Android 13 上还会
        // 因为包名由 user 名单还是系统名单管理而走不同分支），写完后直接回读一遍名单，以真实状态为准。
        val present = isInPowerWhitelist(controller, packageName)
        if (present != null && present != noRestrict) {
            Logger.warn(
                "Doze 白名单未达预期[$packageName]: 期望 noRestrict=$noRestrict，实际在名单=$present"
            )
        }
        return present?.let { it == noRestrict } ?: written
    }

    /** 加入 Doze 白名单，逐版本尝试系统实际暴露的三个入口。 */
    private fun addToPowerWhitelist(controller: Any, packageName: String): Boolean {
        val attempts: List<() -> Any?> = listOf(
            { XReflect.callMethod(controller, "addPowerSaveWhitelistAppsInternal", arrayListOf(packageName)) },
            { XReflect.callMethod(controller, "addPowerSaveWhitelistAppInternal", packageName) },
            { XReflect.callMethod(controller, "addPowerSaveWhitelistApp", packageName) }
        )
        return attempts.any { call ->
            runCatching {
                when (val result = call()) {
                    is Int -> result > 0
                    is Boolean -> result
                    else -> true
                }
            }.getOrDefault(false)
        }
    }

    /** 从 Doze 白名单移除；Android 13 上 `removePowerSaveWhitelistAppInternal` 只吃包名。 */
    private fun removeFromPowerWhitelist(controller: Any, packageName: String): Boolean {
        val attempts: List<() -> Any?> = listOf(
            { XReflect.callMethod(controller, "removePowerSaveWhitelistAppInternal", packageName) },
            { XReflect.callMethod(controller, "removePowerSaveWhitelistAppInternal", packageName, 0) },
            { XReflect.callMethod(controller, "removePowerSaveWhitelistApp", packageName) }
        )
        return attempts.any { call ->
            runCatching {
                when (val result = call()) {
                    is Boolean -> result
                    is Int -> result > 0
                    else -> true
                }
            }.getOrDefault(false)
        }
    }

    /** 回读 Doze 白名单，返回 null 表示当前系统没有可用的回读入口。 */
    private fun isInPowerWhitelist(controller: Any, packageName: String): Boolean? {
        val list = runCatching {
            XReflect.callMethod(controller, "getFullPowerWhitelistInternal") as? Array<*>
        }.getOrNull() ?: return null
        return list.any { it?.toString()?.trim() == packageName }
    }

    private fun applyAppOps(packageName: String, noRestrict: Boolean): Boolean {
        val context = SystemState.systemContext ?: return false
        val manager = context.getSystemService(AppOpsManager::class.java) ?: return false
        val primaryUid = SystemState.getPackageUid(packageName)
        val dualUid = SystemState.getDualPackageUid(packageName)
        val targetUids = listOf(primaryUid, dualUid).filter { it > 0 }.distinct()
        if (targetUids.isEmpty()) return false
        val mode = if (noRestrict) AppOpsManager.MODE_ALLOWED else AppOpsManager.MODE_DEFAULT
        var anySuccess = false
        for (uid in targetUids) {
            if (applyAppOpsForUid(manager, uid, packageName, mode)) {
                anySuccess = true
            }
        }
        return anySuccess
    }

    private fun applyAppOpsForUid(
        manager: AppOpsManager,
        uid: Int,
        packageName: String,
        mode: Int
    ): Boolean {
        // 入口在版本间挪过位置：Android 14 的 framework.jar 里已经没有 setModeInternal，
        // 只剩 public 的 setMode(op, uid, pkg, mode) 与带 attributionTag 的重载，
        // 所以逐条尝试，命中即返回。
        val variants = listOf(
            arrayOf<Any?>(OP_RUN_ANY_IN_BACKGROUND, uid, packageName, mode),
            arrayOf<Any?>(OP_RUN_ANY_IN_BACKGROUND, uid, packageName, null, mode),
            arrayOf<Any?>(OP_RUN_ANY_IN_BACKGROUND, uid, packageName, mode, null)
        )
        for (name in listOf("setModeInternal", "setMode")) {
            for (args in variants) {
                val ok = runCatching {
                    XReflect.callMethod(manager, name, *args)
                    true
                }.getOrDefault(false)
                if (ok) {
                    Logger.debug("AppOps $name 命中[$packageName($uid)] args=${args.size}")
                    return true
                }
            }
        }
        return false
    }

    /**
     * 写 PowerKeeper 的 `user_configure.db.userTable.bgControl`。
     *
     * 系统设置里「无限制 / 智能推荐」这一栏读的就是它，`MILLET_NO_RESTRICT_APP` 也是 PowerKeeper
     * 从这张表投影出来的。只写系统键而不写这张表的话，PowerKeeper 下次重建名单会冲掉本模块
     * 的改动（它会把名单项换回库里那几行）。
     */
    private fun applyPowerKeeperTable(packageName: String, noRestrict: Boolean): Boolean {
        val context = SystemState.systemContext ?: return false
        val uri = Uri.parse(PK_USER_TABLE_URI)
        val target = if (noRestrict) BG_NO_RESTRICT else BG_DEFAULT
        val values = ContentValues().apply { put(COL_BG_CONTROL, target) }
        val userIds = buildList {
            add(0)
            if (SystemState.getDualPackageUid(packageName) > 0) {
                add(io.github.univers629.millet.core.AppUidUtils.DUAL_APP_USER_ID)
            }
        }
        val result = runCatching {
            var applied = false
            for (userId in userIds) {
                val updated = context.contentResolver.update(
                    uri,
                    values,
                    "$COL_PKG_NAME=? AND $COL_USER_ID=?",
                    arrayOf(packageName, userId.toString())
                )
                if (updated > 0) {
                    applied = true
                    continue
                }
                // 库中没有该包的行：仅补充「无限制」一种，恢复默认时不创建新行
                if (!noRestrict) continue
                val row = ContentValues().apply {
                    put(COL_USER_ID, userId)
                    put(COL_PKG_NAME, packageName)
                    put(COL_BG_CONTROL, target)
                }
                if (context.contentResolver.insert(uri, row) != null) {
                    applied = true
                }
            }
            applied
        }
        return result.getOrElse {
            Logger.warn(
                "PowerKeeper 配置库写入失败[$packageName]: ${it.javaClass.simpleName}: ${it.message}"
            )
            false
        }
    }

    private fun applyNoRestrictSetting(packageName: String, noRestrict: Boolean): Boolean {
        val context = SystemState.systemContext ?: return false
        val cr = context.contentResolver
        val current = Settings.System.getString(cr, SETTING_MILLET_NO_RESTRICT_APP) ?: ""
        val packages = LinkedHashSet(
            current.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        )
        val changed = if (noRestrict) packages.add(packageName) else packages.remove(packageName)
        if (!changed) return false
        return runCatching {
            Settings.System.putString(
                cr,
                SETTING_MILLET_NO_RESTRICT_APP,
                packages.joinToString(", ")
            )
        }.getOrElse {
            Logger.warn("写系统无限制名单失败[$packageName]: ${it.javaClass.simpleName}: ${it.message}")
            false
        }
    }

    /** 供诊断弹窗使用：读系统当前的无限制名单（免 root）。 */
    fun isNoRestrict(packageName: String): Boolean {
        val context = SystemState.systemContext ?: return false
        val raw = runCatching {
            Settings.System.getString(context.contentResolver, SETTING_MILLET_NO_RESTRICT_APP)
        }.getOrNull() ?: return false
        return raw.split(',').any { it.trim() == packageName }
    }
}
