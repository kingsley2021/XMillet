package io.github.univers629.millet.system

import android.content.Context
import android.content.pm.ApplicationInfo
import io.github.univers629.millet.core.UidPackageCache
import io.github.univers629.millet.core.ActiveImeTracker
import io.github.univers629.millet.core.AppUidUtils
import io.github.univers629.millet.core.XReflect
import io.github.univers629.millet.core.Logger
import io.github.univers629.millet.core.PrefReader
import io.github.univers629.millet.core.PrefsSnapshot
import io.github.univers629.millet.core.RomCapabilities
import io.github.univers629.millet.core.VersionGate
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.locks.ReentrantLock

/**
 * system_server 作用域全局状态、服务句柄池与进程快照缓存。
 *
 * 修正缺陷 #1：
 * 原模块用单一静态槽 `systemContext` 同时承载 `GreezeManagerService` 与 `AurogonImmobulusMode`：
 * - `isUidFrozen(int)` 仅存在于 `GreezeManagerService`
 * - `checkAppFgService(int)` 仅存在于 `AurogonImmobulusMode`
 * 本实现严格拆分为 `greezeService`、`aurogonService`、`amsInstance`、`deviceIdleController` 四个独立句柄，
 * 各自缓存、各自判空、互不覆盖。
 */
object SystemState {

    /** 全局初始化总闸门：GreezeManagerService 构造完成后才置为 true，此前所有干预逻辑不生效 */
    @Volatile
    var isGreezeReady: Boolean = false

    /** 模块 APK 最近一次更新时间戳（用于检测覆盖更新） */
    @Volatile
    var moduleLastUpdateTime: Long = -1L

    /** S2 捕获的 com.miui.server.greeze.GreezeManagerService 实例（供 isUidFrozen / getFrozenUids 调用） */
    @Volatile
    var greezeService: Any? = null

    /** S3 捕获的 com.miui.server.greeze.AurogonImmobulusMode 实例（供 checkAppFgService / mMessageApp 调用） */
    @Volatile
    var aurogonService: Any? = null

    /** S11 捕获的 com.android.server.am.ActivityManagerService 实例（供获取系统 Context） */
    @Volatile
    var amsInstance: Any? = null

    /** S12 捕获的 com.android.server.DeviceIdleController 实例（供 Doze 状态机与省电白名单调用） */
    @Volatile
    var deviceIdleController: Any? = null

    /** S20 记录最近一次因 toFront/Activity Start 解冻的应用包名（用于配合 screen on 例外放行） */
    @Volatile
    var lastToFrontPackage: String = ""

    /**
     * 判断包名当前是否处于系统前台或用户可见状态（如分屏/小窗/顶层 Activity）。
     *
     * 先匹配 [lastToFrontPackage]；否则读 [ForegroundState] 的异步快照。快照缺失时按「可能在前台」
     * 放行，避免钩子线程为了判定去等待平台锁（曾因此在 WMS 锁内等 AMS 锁，触发 watchdog 重启）。
     */
    fun isPackageCurrentlyForeground(packageName: String?): Boolean {
        if (packageName.isNullOrEmpty()) return false
        if (lastToFrontPackage == packageName) return true
        // 快照不可用时按「可能在前台」放行：钩子线程不能为了判定去等待平台锁。
        return ForegroundState.isForeground(packageName) ?: true
    }

    /** S2 构造器直接传入的系统 Context 备份 */
    @Volatile
    var greezeContext: Context? = null

    /**
     * 多源兜底获取 system_server Context（对应原模块 AbstractC0207.getSystemContext）：
     * 1. S11 捕获的 ActivityManagerService.mContext
     * 2. S2 捕获的 GreezeManagerService 构造参数 Context
     * 3. ActivityThread.currentActivityThread().mSystemContext / ActivityManagerService.self().mContext
     */
    val systemContext: Context?
        get() {
            val ams = amsInstance
            if (ams != null) {
                try {
                    val ctx = XReflect.getObjectField(ams, "mContext") as? Context
                    if (ctx != null) return ctx
                } catch (_: Throwable) {
                }
            }
            greezeContext?.let { return it }
            try {
                val atClass = Class.forName("android.app.ActivityThread")
                val currentAt = XReflect.callStaticMethod(atClass, "currentActivityThread")
                if (currentAt != null) {
                    val sysCtx = XReflect.getObjectField(currentAt, "mSystemContext") as? Context
                    if (sysCtx != null) return sysCtx
                }
            } catch (_: Throwable) {
            }
            return try {
                val amsClass = Class.forName("com.android.server.am.ActivityManagerService")
                val selfAms = XReflect.callStaticMethod(amsClass, "self")
                if (selfAms != null) {
                    amsInstance = selfAms
                    XReflect.getObjectField(selfAms, "mContext") as? Context
                } else {
                    null
                }
            } catch (_: Throwable) {
                null
            }
        }

    // ==================== 1. GreezeManagerService 反射方法封装 ====================

    /**
     * 查询指定 uid 当前是否处于冻结态。
     * 真机声明：`com.miui.server.greeze.GreezeManagerService#isUidFrozen(int) : boolean`
     */
    fun isUidFrozen(uid: Int): Boolean {
        val service = greezeService ?: return false
        return try {
            (XReflect.callMethod(service, "isUidFrozen", uid) as? Boolean) ?: false
        } catch (t: Throwable) {
            Logger.error("call isUidFrozen($uid) failed", t)
            false
        }
    }

    /**
     * 获取当前所有已冻结的 uid 原始整型数组。
     */
    fun getFrozenUidsArray(): IntArray {
        val service = greezeService ?: return IntArray(0)
        return try {
            val raw = XReflect.callMethod(service, "getFrozenUids", 9999)
            when (raw) {
                is IntArray -> raw
                is Collection<*> -> raw.mapNotNull { it as? Int }.toIntArray()
                else -> IntArray(0)
            }
        } catch (_: Throwable) {
            IntArray(0)
        }
    }

    /** 查询失败或返回类型不兼容时返回 null，成功时保留合法空数组。 */
    fun getFrozenUidsArrayOrNull(): IntArray? {
        val service = greezeService ?: return null
        return try {
            val raw = XReflect.callMethod(service, "getFrozenUids", 9999)
            when (raw) {
                is IntArray -> raw
                is Collection<*> -> raw.map { it as? Int ?: return null }.toIntArray()
                else -> null
            }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 一键解冻当前所有已冻结的 UID（供 XMillet 主页快捷操作调用）。
     * @return 触发解冻的 UID 数量
     */
    fun thawAllFrozenUids(): Int {
        val service = greezeService ?: return 0
        val uids = getFrozenUidsArray()
        if (uids.isEmpty()) return 0
        return FreezeStatsRecorder.withModuleBatch(uids.toList()) { accepted ->
            var count = 0
            for (uid in uids) {
                try {
                    val result = XReflect.callMethod(service, "thawUid", uid, 1000, "toFrontActivity")
                    if (result == true) accepted.add(uid)
                    count++
                } catch (_: Throwable) {
                }
            }
            count
        }
    }

    /**
     * 获取当前所有已冻结的 uid 列表，并格式化为 `应用名@uid` 集合。
     * 真机声明：`com.miui.server.greeze.GreezeManagerService#getFrozenUids(int) : int[]`
     */
    fun getFrozenUidLabels(): Set<String> {
        val uids = getFrozenUidsArray()
        if (uids.isEmpty()) return emptySet()
        val result = LinkedHashSet<String>()
        for (uid in uids) {
            result.add(formatAppLabelAndUid(uid))
        }
        return result
    }

    /**
     * 获取当前所有已冻结的 uid 对应的应用名称集合（用于墓碑日志）。
     */
    fun getFrozenAppNames(): Set<String> {
        val uids = getFrozenUidsArray()
        if (uids.isEmpty()) return emptySet()
        val result = LinkedHashSet<String>()
        for (uid in uids) {
            result.add(formatAppLabelOnly(uid))
        }
        return result
    }

    // ==================== 2. AurogonImmobulusMode 反射方法封装 ====================

    private class MessageProjection {
        val removed = LinkedHashSet<String>()
        val added = LinkedHashSet<String>()
    }

    private class BroadcastProjection {
        val removed = LinkedHashMap<String, MutableList<String>>()
        val added = LinkedHashMap<String, MutableList<String>>()
    }

    private val messageProjections = java.util.IdentityHashMap<MutableList<String>, MessageProjection>()
    private val broadcastProjections =
        java.util.IdentityHashMap<MutableMap<String, MutableList<String>>, BroadcastProjection>()

    internal fun applyReversibleListProjection(
        current: MutableList<String>,
        additions: Set<String>,
        removals: Set<String>,
    ) = syncMessageProjection(current, additions, removals)

    private fun canRestrictMessagePackage(packageName: String, prefs: PrefsSnapshot): Boolean {
        val ctx = systemContext ?: return false
        val token = android.os.Binder.clearCallingIdentity()
        return try {
            @Suppress("DEPRECATION")
            val info = ctx.packageManager.getApplicationInfo(packageName, 0)
            val uid = info.uid
            if (!AppUidUtils.isManagedAppUid(uid) || AppUidUtils.userIdOf(uid) != AppUidUtils.PRIMARY_USER_ID ||
                info.flags and ApplicationInfo.FLAG_SYSTEM != 0 ||
                AppUidUtils.containsPolicyTarget(prefs.whiteAppSet, packageName, uid) ||
                ActiveImeTracker.isProtectedSystemServiceApp(ctx, packageName, prefs, uid) ||
                isPackageCurrentlyForeground(packageName)
            ) return false
            val uidPackages = ctx.packageManager.getPackagesForUid(uid) ?: return false
            if (uidPackages.size != 1 || uidPackages[0] != packageName) return false
            val dualUid = AppUidUtils.dualUidOf(uid)
            if (dualUid > 0 && ctx.packageManager.getPackagesForUid(dualUid)?.contains(packageName) == true) return false
            // 快照不可用时不收紧（按「可能可见」处理），避免在钩子线程里等待 AMS 锁。
            ForegroundState.isForeground(packageName) == false
        } catch (_: Throwable) {
            false
        } finally {
            android.os.Binder.restoreCallingIdentity(token)
        }
    }

    private fun syncMessageProjection(current: MutableList<String>, additions: Set<String>, removals: Set<String>) {
        synchronized(messageProjections) {
            val state = messageProjections.getOrPut(current) { MessageProjection() }
            for (pkg in state.removed.toList()) {
                if (pkg !in removals) {
                    if (pkg !in current) current.add(pkg)
                    state.removed.remove(pkg)
                }
            }
            for (pkg in state.added.toList()) {
                if (pkg !in additions) {
                    current.removeAll(setOf(pkg))
                    state.added.remove(pkg)
                }
            }
            for (pkg in removals) {
                if (current.removeAll(setOf(pkg)) && !state.added.remove(pkg)) state.removed.add(pkg)
            }
            for (pkg in additions) {
                if (pkg !in removals && pkg !in current && current.add(pkg)) state.added.add(pkg)
            }
        }
    }

    /**
     * 查询指定 uid 是否拥有活跃的前台服务（FGS）。
     * 真机声明：`com.miui.server.greeze.AurogonImmobulusMode#checkAppFgService(int) : boolean`
     * 该方法仅存在于 AurogonImmobulusMode，不可在 GreezeManagerService 上调用。
     */
    fun checkAppFgService(uid: Int): Boolean {
        val aurogon = aurogonService
        if (aurogon == null) {
            Logger.error("判断fgs: aurogonService instance is null")
            return false
        }
        return try {
            (XReflect.callMethod(aurogon, "checkAppFgService", uid) as? Boolean) ?: false
        } catch (t: Throwable) {
            Logger.error("call checkAppFgService($uid) failed", t)
            false
        }
    }

    /**
     * 判断指定 UID 当前是否与前台应用存在活跃 Binder 关联。
     *
     * 调用 `GreezeManagerService#isInFgBinderApp(int)` 仅读取其内部叶锁保护的集合，无额外状态副作用；
     * 低版本 ROM 无此方法时返回 false。
     */
    fun isForegroundBinderBound(uid: Int): Boolean {
        if (!AppUidUtils.isManagedAppUid(uid)) return false
        val service = greezeService ?: return false
        if (!RomCapabilities.hasMethod(service.javaClass, "isInFgBinderApp")) return false
        return try {
            (XReflect.callMethod(service, "isInFgBinderApp", uid) as? Boolean) ?: false
        } catch (_: Throwable) {
            false
        }
    }

    private const val LIVE_WALLPAPER_CACHE_TTL_MS = 60_000L

    @Volatile
    private var cachedLiveWallpaperPackages: Set<String> = emptySet()

    @Volatile
    private var lastLiveWallpaperRefreshMs: Long = 0L

    private val liveWallpaperRefreshInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    private val wallpaperRefreshExecutor by lazy {
        java.util.concurrent.Executors.newSingleThreadExecutor { task ->
            Thread(task, "XMillet-WallpaperRefresh").apply { isDaemon = true }
        }
    }

    /** 后台刷新当前生效的动态壁纸包名集合（桌面与锁屏），避免在钩子线程做跨服务查询。 */
    fun refreshLiveWallpaperCacheAsync(force: Boolean = false) {
        val ctx = systemContext ?: return
        val now = android.os.SystemClock.uptimeMillis()
        if (!force && lastLiveWallpaperRefreshMs > 0L && now - lastLiveWallpaperRefreshMs in 0..LIVE_WALLPAPER_CACHE_TTL_MS) {
            return
        }
        if (!liveWallpaperRefreshInFlight.compareAndSet(false, true)) return
        runCatching {
            wallpaperRefreshExecutor.execute {
                val token = android.os.Binder.clearCallingIdentity()
                try {
                    val wm = ctx.getSystemService(android.app.WallpaperManager::class.java)
                        ?: android.app.WallpaperManager.getInstance(ctx)
                    val pkgs = LinkedHashSet<String>()
                    wm?.wallpaperInfo?.packageName?.takeIf { it.isNotEmpty() }?.let(pkgs::add)
                    if (wm != null && VersionGate.isSdkAfter33) {
                        for (which in intArrayOf(1, 2)) {
                            val info = runCatching {
                                XReflect.callMethod(wm, "getWallpaperInfo", which) as? android.app.WallpaperInfo
                            }.getOrNull()
                            info?.packageName?.takeIf { it.isNotEmpty() }?.let(pkgs::add)
                        }
                    }
                    cachedLiveWallpaperPackages = pkgs
                    lastLiveWallpaperRefreshMs = android.os.SystemClock.uptimeMillis()
                } catch (_: Throwable) {
                } finally {
                    android.os.Binder.restoreCallingIdentity(token)
                    liveWallpaperRefreshInFlight.set(false)
                }
            }
        }.onFailure {
            liveWallpaperRefreshInFlight.set(false)
        }
    }

    /** 返回当前检测到的动态壁纸包名集合（合并 ROM 内存字段与异步 WallpaperManager 快照）。 */
    fun getActiveLiveWallpaperPackages(): Set<String> {
        refreshLiveWallpaperCacheAsync()
        val result = LinkedHashSet<String>()
        result.addAll(cachedLiveWallpaperPackages)
        val aurogon = aurogonService
        if (aurogon != null) {
            for (fieldName in arrayOf("mDeskWallPaperPackageName", "mLockWallPaperPackageName", "mWallPaperPackageName")) {
                val field = XReflect.findFieldOrNull(aurogon.javaClass, fieldName) ?: continue
                val pkg = runCatching { field.get(aurogon) as? String }.getOrNull()
                if (!pkg.isNullOrEmpty()) result.add(pkg)
            }
        }
        return result
    }

    /**
     * 判断指定应用是否为当前生效的桌面/锁屏动态壁纸。
     *
     * 优先无锁读取 `AurogonImmobulusMode` 已缓存的壁纸字段（覆盖 MIUI 14 至 HyperOS 4），
     * 并结合后台刷新的 `WallpaperManager` 快照覆盖未及时同步的场景。
     */
    fun isActiveLiveWallpaper(uid: Int, packageName: String?): Boolean {
        if (!AppUidUtils.isManagedAppUid(uid) || packageName.isNullOrEmpty()) return false
        val aurogon = aurogonService
        if (aurogon != null) {
            val deskUid = XReflect.getIntFieldOr(aurogon, "mDeskWallPaperUid", default = -1)
            val lockUid = XReflect.getIntFieldOr(aurogon, "mLockWallPaperUid", default = -1)
            if ((deskUid > 0 && deskUid == uid) || (lockUid > 0 && lockUid == uid)) {
                return true
            }
            for (fieldName in arrayOf("mDeskWallPaperPackageName", "mLockWallPaperPackageName", "mWallPaperPackageName")) {
                val field = XReflect.findFieldOrNull(aurogon.javaClass, fieldName) ?: continue
                val pkg = runCatching { field.get(aurogon) as? String }.getOrNull()
                if (pkg == packageName) return true
            }
        }
        refreshLiveWallpaperCacheAsync()
        return packageName in cachedLiveWallpaperPackages
    }

    private data class WidgetCacheEntry(val bound: Boolean, val refreshedAtMs: Long)

    private const val WIDGET_CACHE_TTL_MS = 30_000L
    private val widgetBoundCache = ConcurrentHashMap<String, WidgetCacheEntry>()

    /**
     * 判断指定应用当前是否已绑定桌面小组件。
     *
     * 通过 `AppWidgetManager#isBoundWidgetPackage(String, int)` 查询，兼容 MIUI 14 至 HyperOS 4；
     * 服务端要求 `Binder.getCallingUid() == SYSTEM_UID`，调用前清除调用身份并做短时缓存。
     */
    fun isWidgetApp(uid: Int, packageName: String?): Boolean {
        if (!AppUidUtils.isManagedAppUid(uid) || packageName.isNullOrEmpty()) return false
        val userId = AppUidUtils.userIdOf(uid)
        val key = "$userId:$packageName"
        val now = android.os.SystemClock.uptimeMillis()
        widgetBoundCache[key]?.takeIf { now - it.refreshedAtMs in 0..WIDGET_CACHE_TTL_MS }?.let {
            return it.bound
        }
        val ctx = systemContext ?: return false
        val token = android.os.Binder.clearCallingIdentity()
        val bound = try {
            val awm = ctx.getSystemService(android.appwidget.AppWidgetManager::class.java)
                ?: android.appwidget.AppWidgetManager.getInstance(ctx)
            if (awm != null) {
                (XReflect.callMethod(awm, "isBoundWidgetPackage", packageName, userId) as? Boolean) ?: false
            } else {
                false
            }
        } catch (_: Throwable) {
            false
        } finally {
            android.os.Binder.restoreCallingIdentity(token)
        }
        if (widgetBoundCache.size >= 256) widgetBoundCache.clear()
        widgetBoundCache[key] = WidgetCacheEntry(bound, now)
        return bound
    }

    /**
     * 将模块配置的 `messageAppSet` 同步到 `AurogonImmobulusMode.mMessageApp` 静态列表，
     * 并在 SDK > 33 时更新 `mBroadcastTargetWhiteList`。
     */
    @Suppress("UNCHECKED_CAST")
    fun syncMessageAppsToAurogon() {
        val aurogon = aurogonService ?: return
        // ROM 消息名单按包名生效，双开策略不能投影为主用户包名。
        val prefs = PrefReader.systemPrefs
        val suppressMessageApps = LinkedHashSet<String>().apply {
            addAll(AppUidUtils.policyPackagesForUser(prefs.forceFreezeSet, AppUidUtils.PRIMARY_USER_ID))
            addAll(AppUidUtils.policyPackagesForUser(prefs.blockThawSet, AppUidUtils.PRIMARY_USER_ID))
            addAll(AppUidUtils.policyPackagesForUser(prefs.ignoreNetworkWakeSet, AppUidUtils.PRIMARY_USER_ID))
        }.filterTo(LinkedHashSet()) { canRestrictMessagePackage(it, prefs) }
        val messageApps = AppUidUtils.policyPackagesForUser(prefs.messageAppSet, AppUidUtils.PRIMARY_USER_ID)
            .filterTo(LinkedHashSet()) { it !in suppressMessageApps }
        try {
            val list = XReflect.getStaticObjectField(aurogon.javaClass, "mMessageApp") as? MutableList<String>
            if (list != null) {
                syncMessageProjection(list, messageApps, suppressMessageApps)
            }
        } catch (_: Throwable) {
            // 部分 MIUI 版本字段为实例成员
            try {
                val instanceList = XReflect.getObjectField(aurogon, "mMessageApp") as? MutableList<String>
                if (instanceList != null) {
                    syncMessageProjection(instanceList, messageApps, suppressMessageApps)
                }
            } catch (t: Throwable) {
                Logger.error("syncMessageAppsToAurogon mMessageApp failed", t)
            }
        }

        if (VersionGate.isSdkAfter33) {
            try {
                val targetMap = XReflect.getObjectField(
                    aurogon,
                    "mBroadcastTargetWhiteList"
                ) as? MutableMap<String, MutableList<String>>
                if (targetMap != null) {
                    val allowedActions = arrayListOf(
                        "android.net.wifi.STATE_CHANGE",
                        "android.net.conn.CONNECTIVITY_CHANGE"
                    )
                    synchronized(broadcastProjections) {
                        val state = broadcastProjections.getOrPut(targetMap) { BroadcastProjection() }
                        for ((pkg, actions) in state.removed.toMap()) {
                            if (pkg !in suppressMessageApps) {
                                if (pkg !in targetMap) targetMap[pkg] = actions
                                state.removed.remove(pkg)
                            }
                        }
                        for ((pkg, actions) in state.added.toMap()) {
                            if (pkg !in messageApps) {
                                if (targetMap[pkg] === actions && actions == allowedActions) targetMap.remove(pkg)
                                state.added.remove(pkg)
                            }
                        }
                        for (pkg in suppressMessageApps) {
                            val actions = targetMap.remove(pkg) ?: continue
                            val added = state.added.remove(pkg)
                            if (actions !== added || actions != allowedActions) state.removed[pkg] = actions
                        }
                        for (pkg in messageApps) {
                            if (pkg !in targetMap) {
                                val actions = ArrayList(allowedActions)
                                targetMap[pkg] = actions
                                state.added[pkg] = actions
                            }
                        }
                    }
                }
            } catch (_: Throwable) {
            }
        }

        syncAlarmAllowList(messageApps, suppressMessageApps)
    }

    @Suppress("UNCHECKED_CAST")
    private fun syncAlarmAllowList(additions: Set<String>, removals: Set<String>) {
        val greeze = greezeService ?: return
        val field = XReflect.findFieldOrNull(greeze.javaClass, "mAurogonAlarmAllowList") ?: return
        val list = runCatching {
            if (java.lang.reflect.Modifier.isStatic(field.modifiers)) {
                field.get(null)
            } else {
                field.get(greeze)
            } as? MutableList<String>
        }.getOrNull() ?: return
        syncMessageProjection(list, additions, removals)
    }

    // ==================== 3. 包名 / UID / /proc 工具方法 ====================

    /**
     * 根据 UID 反查主包名（失败时返回 null）。
     */
    fun getPackageNameForUid(uid: Int): String? {
        if (uid <= 0) return null
        // 钩子线程可能持有平台锁，这里只读缓存；未命中时投递后台解析（见 UidPackageCache）。
        UidPackageCache.lookup(uid)?.let { return it }
        UidPackageCache.requestResolve(uid, systemContext)
        return null
    }

    /** 统计仅归属到完整 UID 的唯一包名；共享 UID、查询失败或未知归属返回 null。 */
    fun getUniquePackageNameForStatsUid(uid: Int): String? {
        if (uid <= 0) return null
        val ctx = systemContext ?: return null
        val token = android.os.Binder.clearCallingIdentity()
        return try {
            ctx.packageManager.getPackagesForUid(uid)?.singleOrNull()?.takeIf { it.isNotBlank() }
        } catch (_: Throwable) {
            null
        } finally {
            android.os.Binder.restoreCallingIdentity(token)
        }
    }

    /**
     * 根据包名查询 UID（失败时返回 -1）。
     */
    fun getPackageUid(packageName: String): Int {
        val ctx = systemContext ?: return -1
        return try {
            @Suppress("DEPRECATION")
            ctx.packageManager.getApplicationInfo(packageName, 0).uid
        } catch (_: Throwable) {
            -1
        }
    }

    /**
     * 判断指定 UID 是否为系统应用（FLAG_SYSTEM，对应原模块误命名的 isUidForeground）。
     */
    fun isSystemAppUid(uid: Int): Boolean {
        if (uid < 10000) return true
        val ctx = systemContext ?: return false
        return try {
            val pkg = ctx.packageManager.getPackagesForUid(uid)?.firstOrNull() ?: return false
            @Suppress("DEPRECATION")
            val appInfo = ctx.packageManager.getApplicationInfo(pkg, 0)
            (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 格式化纯应用名（uid == 99000 特判为 Android System WebView，对应原模块 AbstractC0207.getAppLabel）。
     */
    fun formatAppLabelOnly(uid: Int): String {
        if (uid == 99000) return "Android System WebView"
        return resolveAppLabelCached(uid) ?: "uid:$uid"
    }

    /** uid -> (应用名, 写入时间)。墓碑日志每次冻结/解冻都会对整张冻结表取名，避免反复查询 PMS。 */
    private val appLabelCache = ConcurrentHashMap<Int, Pair<String, Long>>()

    /** 缓存有效期：应用改名/更新后最多 10 分钟内刷新，无需监听包变更广播。 */
    private const val APP_LABEL_CACHE_TTL_MS = 10 * 60 * 1000L
    private const val APP_LABEL_CACHE_MAX = 512

    private fun resolveAppLabelCached(uid: Int): String? {
        val now = android.os.SystemClock.uptimeMillis()
        appLabelCache[uid]?.let { (label, ts) ->
            if (now - ts < APP_LABEL_CACHE_TTL_MS) return label
        }
        val ctx = systemContext ?: return null
        val token = android.os.Binder.clearCallingIdentity()
        val label = try {
            val pm = ctx.packageManager
            val pkg = pm.getPackagesForUid(uid)?.firstOrNull() ?: return null
            @Suppress("DEPRECATION")
            val appInfo = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (_: Throwable) {
            return null
        } finally {
            android.os.Binder.restoreCallingIdentity(token)
        }
        if (appLabelCache.size >= APP_LABEL_CACHE_MAX) appLabelCache.clear()
        appLabelCache[uid] = label to now
        return label
    }

    /** 供包变更场景主动失效（可选调用）。 */
    fun invalidateAppLabel(uid: Int) {
        appLabelCache.remove(uid)
    }

    /**
     * 格式化 `应用名@uid`（用于 S6 freezeUids 日志）。
     */
    fun formatAppLabelAndUid(uid: Int): String {
        val label = resolveAppLabelCached(uid) ?: return "uid@$uid"
        return "$label@$uid"
    }

    /**
     * 从 `/proc/<pid>/status` 反查进程真实 UID（专用于 S18 thawProcess，所有异常判空兜底返回 -1）。
     */
    fun readUidFromProcStatus(pid: Int): Int {
        if (pid <= 0) return -1
        return try {
            val statusFile = File("/proc/$pid/status")
            if (!statusFile.exists() || !statusFile.canRead()) return -1
            statusFile.useLines { lines ->
                for (line in lines) {
                    if (line.startsWith("Uid:")) {
                        val parts = line.substringAfter("Uid:").trim().split(Regex("\\s+"))
                        val uid = parts.firstOrNull()?.toIntOrNull()
                        if (uid != null) return uid
                    }
                }
            }
            -1
        } catch (_: Throwable) {
            -1
        }
    }

    // ==================== 4. ProcessRecord 缓存表（供 S4/S5/S9/S10/S15/S19 使用） ====================

    data class CachedProcessState(
        val processRecord: Any,
        val processName: String,
        val packageName: String,
        val uid: Int,
        val runningUid: Int,
        val userId: Int,
        val isSystemApp: Boolean,
        val isIsolated: Boolean,
        val lock: ReentrantLock = ReentrantLock()
    )

    /** processName -> (uid -> CachedProcessState) */
    private val processCacheByName = ConcurrentHashMap<String, ConcurrentHashMap<Int, CachedProcessState>>()

    /**
     * 从 AOSP ProcessRecord 反射提取进程快照（失败返回 null）。
     */
    fun extractProcessStateOrNull(processRecord: Any?): CachedProcessState? {
        if (processRecord == null) return null
        return try {
            val appInfo = XReflect.getObjectField(processRecord, "info") as? ApplicationInfo
                ?: return null
            val pkgName = appInfo.packageName ?: return null
            val appUid = appInfo.uid
            val runningUid = XReflect.getIntFieldOr(processRecord, "uid", default = appUid)
            val userId = XReflect.getIntFieldOr(processRecord, "userId", default = 0)
            val procName = (XReflect.getObjectField(processRecord, "processName") as? String)
                ?: pkgName
            val isSys = pkgName == "android" ||
                appUid < 10000 ||
                (appInfo.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0

            CachedProcessState(
                processRecord = processRecord,
                processName = procName,
                packageName = pkgName,
                uid = appUid,
                runningUid = runningUid,
                userId = userId,
                isSystemApp = isSys,
                isIsolated = runningUid != appUid
            )
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * S4：缓存新增进程。
     */
    fun cacheProcessRecord(state: CachedProcessState) {
        val byUid = processCacheByName.getOrPut(state.processName) { ConcurrentHashMap() }
        byUid[state.uid] = state
    }

    /**
     * S15：移除已退出的进程缓存。
     */
    fun removeCachedProcessRecord(processName: String?, uid: Int): CachedProcessState? {
        if (processName.isNullOrEmpty()) return null
        val byUid = processCacheByName[processName] ?: return null
        val removed = byUid.remove(uid)
        if (byUid.isEmpty()) {
            processCacheByName.remove(processName)
        }
        return removed
    }

    /**
     * S5 / S19：根据 ProcessRecord 查找已缓存的进程状态。
     */
    fun findCachedProcess(processRecord: Any?): CachedProcessState? {
        if (processRecord == null) return null
        return try {
            val procName = XReflect.getObjectField(processRecord, "processName") as? String
                ?: return null
            val appInfo = XReflect.getObjectField(processRecord, "info") as? ApplicationInfo
                ?: return null
            processCacheByName[procName]?.get(appInfo.uid)
        } catch (_: Throwable) {
            null
        }
    }

    // ==================== 5. PID 冻结状态记账表（供 S16/S17/S18 simplifyLog 使用） ====================

    data class UidPidFreezeRecord(
        @Volatile var lastReason: String = "",
        @Volatile var allFrozen: Boolean = false,
        val pidFrozenMap: ConcurrentHashMap<Int, Boolean> = ConcurrentHashMap()
    )

    private val pidFreezeLock = ReentrantLock()
    private val uidPidFreezeTable = ConcurrentHashMap<Int, UidPidFreezeRecord>()
    private val recentTombstoneReasons = CopyOnWriteArrayList<String>()

    fun isPidTracked(uid: Int, pid: Int): Boolean {
        pidFreezeLock.lock()
        try {
            return uidPidFreezeTable[uid]?.pidFrozenMap?.containsKey(pid) == true
        } finally {
            pidFreezeLock.unlock()
        }
    }

    fun recordPidFreezeState(uid: Int, pid: Int, frozen: Boolean, reason: String): UidPidFreezeRecord {
        var emitThawTombstone = false
        var emitFreezeTombstone = false
        var effectiveReason = reason
        val record: UidPidFreezeRecord

        pidFreezeLock.lock()
        try {
            record = uidPidFreezeTable.getOrPut(uid) { UidPidFreezeRecord() }
            record.lastReason = reason
            record.pidFrozenMap[pid] = frozen
            val nowAllFrozen = record.pidFrozenMap.isNotEmpty() && record.pidFrozenMap.values.all { it }
            val wasAllFrozen = record.allFrozen

            if (wasAllFrozen && !nowAllFrozen) {
                record.allFrozen = false
                if (isGreezeReady && PrefReader.systemPrefs.simplifyLog) {
                    emitThawTombstone = true
                    effectiveReason = record.lastReason
                }
            } else if (!wasAllFrozen && nowAllFrozen) {
                record.allFrozen = true
                if (isGreezeReady && PrefReader.systemPrefs.simplifyLog) {
                    if (recentTombstoneReasons.size >= 2) {
                        recentTombstoneReasons.removeAt(0)
                    }
                    recentTombstoneReasons.add(record.lastReason)
                    emitFreezeTombstone = true
                    effectiveReason = record.lastReason
                }
            }
        } finally {
            pidFreezeLock.unlock()
        }

        if (emitThawTombstone || emitFreezeTombstone) {
            // 完整冻结列表仅在详细日志(isLog)下输出；默认只输出数量，避免每次冻结/解冻遍历整张冻结表取应用名。
            val frozenSummary = if (PrefReader.systemPrefs.isLog) {
                "冻结列表:${getFrozenAppNames()}"
            } else {
                "冻结数:${getFrozenUidsArray().size}"
            }
            val tag = if (emitThawTombstone) "墓碑日志(T)" else "墓碑日志(F)"
            val action = if (emitThawTombstone) "解冻应用" else "冻结应用"
            Logger.alwaysWarn("$tag:由于[$effectiveReason],$action[${formatAppLabelOnly(uid)}],$frozenSummary")
        }
        return record
    }

    fun removePidFromFreezeTable(uid: Int, pid: Int) {
        pidFreezeLock.lock()
        try {
            val record = uidPidFreezeTable[uid] ?: return
            record.pidFrozenMap.remove(pid)
            if (record.pidFrozenMap.isEmpty()) {
                uidPidFreezeTable.remove(uid)
            }
        } finally {
            pidFreezeLock.unlock()
        }
    }

    fun getUidFreezeSummary(uid: Int): Triple<Int, Int, Map<Int, Boolean>> {
        pidFreezeLock.lock()
        try {
            val record = uidPidFreezeTable[uid] ?: return Triple(0, 0, emptyMap())
            val snapshot = LinkedHashMap(record.pidFrozenMap)
            val frozenCount = snapshot.values.count { it }
            return Triple(frozenCount, snapshot.size, snapshot)
        } finally {
            pidFreezeLock.unlock()
        }
    }

    /**
     * 热重载后重新拿回框架服务句柄。
     *
     * 热重载会让新一代代码的静态缓存全部为空，而这些句柄原本只在开机时的构造器
     * Hook（S2 / S3 / S11 / S12）里被捕获。不重新获取的话，新一代的所有闸门都会因为
     * `isGreezeReady == false` 直接放行 —— Hook 已安装但不生效。
     *
     * - `greeze` / `deviceidle` 都在 ServiceManager 里注册过（服务名 `greezer` /
     *   `deviceidle`），系统进程内 `getService(name)` 拿到的就是服务实现本身；
     * - `AurogonImmobulusMode` 不是独立 binder，从 greeze 实例的字段/无参方法里找。
     *
     * @return true 表示至少拿回了 greeze 句柄
     */
    fun reacquireFrameworkServices(): Boolean {
        if (greezeService == null) {
            greezeService = resolveSystemService("greezer")
        }
        val greeze = greezeService ?: run {
            isGreezeReady = false
            return false
        }
        isGreezeReady = true
        if (aurogonService == null) {
            aurogonService = findInstanceOfType(greeze, CLASS_AUROGON_MODE)
        }
        if (deviceIdleController == null) {
            deviceIdleController = unwrapServiceImplementation(
                resolveSystemService("deviceidle") ?: resolveSystemService("device_idle"),
                CLASS_DEVICE_IDLE_CONTROLLER,
            )
        }
        aurogonService?.let { aurogon ->
            hasAurogonIsUidValid = io.github.univers629.millet.core.RomCapabilities.hasMethod(
                CLASS_AUROGON_MODE,
                "isUidValid",
                aurogon.javaClass.classLoader
            )
            syncDualAppSupportToAurogon(io.github.univers629.millet.core.PrefReader.systemPrefs.freezeDualApp)
        }
        ForegroundState.start(systemContext)
        return true
    }

    @Volatile
    var hasAurogonIsUidValid: Boolean = false

    fun getDualPackageUid(packageName: String): Int {
        val ctx = systemContext ?: return 0
        val primaryUid = getPackageUid(packageName)
        val dualUid = io.github.univers629.millet.core.AppUidUtils.dualUidOf(primaryUid)
        if (dualUid <= 0) return 0
        return try {
            val pkgs = ctx.packageManager.getPackagesForUid(dualUid)
            if (pkgs != null && packageName in pkgs) dualUid else 0
        } catch (_: Throwable) {
            0
        }
    }

    /**
     * 免 Root 枚举双开空间（userId = 999）已安装的受管应用清单，编码为 `packageName|uid|flags` 行文本。
     */
    @Suppress("UNCHECKED_CAST", "DEPRECATION")
    fun queryDualAppsSnapshot(): String {
        val ctx = systemContext ?: return ""
        val token = android.os.Binder.clearCallingIdentity()
        return try {
            val pm = ctx.packageManager
            val dualApps: List<ApplicationInfo> = runCatching {
                XReflect.callMethod(
                    pm,
                    "getInstalledApplicationsAsUser",
                    0,
                    io.github.univers629.millet.core.AppUidUtils.DUAL_APP_USER_ID
                ) as? List<ApplicationInfo>
            }.getOrNull() ?: run {
                val primaryList = runCatching { pm.getInstalledApplications(0) }.getOrDefault(emptyList())
                primaryList.filter { info ->
                    val pkg = info.packageName ?: return@filter false
                    val dualUid = io.github.univers629.millet.core.AppUidUtils.dualUidOf(info.uid)
                    if (dualUid <= 0) return@filter false
                    val pkgs = runCatching { pm.getPackagesForUid(dualUid) }.getOrNull()
                    pkgs != null && pkg in pkgs
                }
            }
            val lines = ArrayList<String>(dualApps.size)
            for (info in dualApps) {
                val pkg = info.packageName?.takeIf { it.isNotEmpty() } ?: continue
                val dualUid = if (io.github.univers629.millet.core.AppUidUtils.isDualAppUid(info.uid)) {
                    info.uid
                } else {
                    io.github.univers629.millet.core.AppUidUtils.dualUidOf(info.uid)
                }
                if (!io.github.univers629.millet.core.AppUidUtils.isDualAppUid(dualUid)) continue
                lines.add("$pkg|$dualUid|${info.flags}")
            }
            lines.joinToString("\n")
        } catch (t: Throwable) {
            Logger.warn("queryDualAppsSnapshot failed: ${t.javaClass.simpleName}: ${t.message}")
            ""
        } finally {
            android.os.Binder.restoreCallingIdentity(token)
        }
    }

    fun syncDualAppSupportToAurogon(enabled: Boolean) {
        val aurogon = aurogonService ?: return
        val clazz = aurogon.javaClass
        val loader = clazz.classLoader
        if (io.github.univers629.millet.core.RomCapabilities.hasField(clazz, "mDoubleEnable")) {
            runCatching { XReflect.findField(clazz, "mDoubleEnable").setBoolean(null, enabled) }
        }
        if (io.github.univers629.millet.core.RomCapabilities.hasField(clazz, "mDoubleAppCtrlCloud")) {
            runCatching {
                val field = clazz.getDeclaredField("mDoubleAppCtrlCloud").apply { isAccessible = true }
                if (java.lang.reflect.Modifier.isStatic(field.modifiers)) {
                    field.setBoolean(null, enabled)
                } else {
                    field.setBoolean(aurogon, enabled)
                }
            }
        }
        if (enabled && io.github.univers629.millet.core.RomCapabilities.hasField(clazz, "ENABLE_DOUBLE_APP")) {
            runCatching {
                val raw = XReflect.getStaticObjectField(clazz, "ENABLE_DOUBLE_APP") as? Collection<*>
                val device = android.os.Build.DEVICE
                if (raw != null && device !in raw) {
                    val updated = ArrayList<String>(raw.size + 1).apply {
                        for (item in raw) {
                            if (item is String) add(item)
                        }
                        add(device)
                    }
                    XReflect.setStaticObjectField(clazz, "ENABLE_DOUBLE_APP", updated)
                }
            }
        }
        if (!hasAurogonIsUidValid) {
            hasAurogonIsUidValid = io.github.univers629.millet.core.RomCapabilities.hasMethod(
                CLASS_AUROGON_MODE,
                "isUidValid",
                loader
            )
        }
    }

    fun isGreezeScreenOff(greezeObj: Any): Boolean? {
        val clazz = greezeObj.javaClass
        return when {
            io.github.univers629.millet.core.RomCapabilities.hasField(clazz, "mScreenOnOff") ->
                runCatching { !XReflect.findField(clazz, "mScreenOnOff").getBoolean(greezeObj) }.getOrNull()
            io.github.univers629.millet.core.RomCapabilities.hasField(clazz, "mScreenOn") ->
                runCatching { !XReflect.findField(clazz, "mScreenOn").getBoolean(greezeObj) }.getOrNull()
            else -> null
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun handleNotifyExecuteServiceForDualUid(greezeObj: Any, uid: Int) {
        try {
            val excuteList = XReflect.getObjectField(greezeObj, "mExcuteServiceList") as? MutableList<Int>
            if (excuteList != null) {
                synchronized(excuteList) {
                    val inFg = (runCatching { XReflect.callMethod(greezeObj, "isAppRunningInFg", uid) as? Boolean }.getOrNull()) ?: false
                    if (uid !in excuteList && !inFg) {
                        excuteList.add(uid)
                    }
                }
            }
            val handler = XReflect.getObjectField(greezeObj, "mHandler") as? android.os.Handler ?: return
            handler.post {
                try {
                    val aurogon = aurogonService ?: runCatching {
                        XReflect.getObjectField(greezeObj, "mImmobulusMode")
                    }.getOrNull()
                    val runningLaunch = (aurogon?.let {
                        runCatching { XReflect.callMethod(it, "isRunningLaunchMode") as? Boolean }.getOrNull()
                    }) ?: false
                    if (!runningLaunch) {
                        XReflect.callMethod(greezeObj, "thawUid", uid, 1000, "Excute Service")
                    }
                } catch (t: Throwable) {
                    Logger.error("handleNotifyExecuteServiceForDualUid post failed($uid)", t)
                }
            }
        } catch (t: Throwable) {
            Logger.error("handleNotifyExecuteServiceForDualUid failed($uid)", t)
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun addDualAppToAurogonTargetList(aurogon: Any, uid: Int) {
        try {
            val targetList = XReflect.getObjectField(aurogon, "mImmobulusTargetList") as? MutableList<Any?> ?: return
            synchronized(targetList) {
                for (existing in targetList) {
                    if (existing != null && runCatching { XReflect.getIntField(existing, "mUid") }.getOrNull() == uid) {
                        return
                    }
                }
                val rawPkg = runCatching {
                    XReflect.callMethod(aurogon, "getPackageNameFromUid", uid) as? String
                }.getOrNull() ?: getPackageNameForUid(uid) ?: return
                val pkg = rawPkg.substringBefore(':')
                if (pkg.contains(".qualcomm") || pkg.contains("com.qti")) return

                val isCts = runCatching { XReflect.callMethod(aurogon, "isCtsApp", pkg) as? Boolean }.getOrNull() ?: false
                if (isCts && !pkg.contains("ctsshim")) {
                    runCatching { XReflect.callMethod(aurogon, "QuitLaunchModeAction", false) }
                    runCatching { XReflect.setBooleanField(aurogon, "mLaunchModeEnabled", false) }
                }

                val appInfoClass = Class.forName(
                    "com.miui.server.greeze.AurogonAppInfo",
                    false,
                    aurogon.javaClass.classLoader
                )
                val ctor = appInfoClass.getDeclaredConstructor(Int::class.javaPrimitiveType!!, String::class.java).apply {
                    isAccessible = true
                }
                val appInfo = ctor.newInstance(uid, pkg)
                val hasIcon = runCatching { XReflect.callMethod(aurogon, "isAppHasIcon", pkg) as? Boolean }.getOrNull() ?: false
                if (hasIcon) {
                    runCatching { XReflect.setBooleanField(appInfo, "hasIcon", true) }
                }
                val isImportant = runCatching {
                    XReflect.callMethod(aurogon, "isSystemOrMiuiImportantApp", pkg) as? Boolean
                }.getOrNull() ?: false
                if (isImportant) {
                    runCatching { XReflect.setBooleanField(appInfo, "isSystemApp", true) }
                }
                targetList.add(appInfo)
            }
        } catch (t: Throwable) {
            Logger.error("addDualAppToAurogonTargetList failed($uid)", t)
        }
    }

    private const val CLASS_AUROGON_MODE = "com.miui.server.greeze.AurogonImmobulusMode"
    private const val CLASS_DEVICE_IDLE_CONTROLLER = "com.android.server.DeviceIdleController"
    private const val CLASS_LIST_APPS_MANAGER = "com.miui.server.greeze.power.ListAppsManager"
    private const val VOICE_ASSIST_PACKAGE = "com.miui.voiceassist"

    private val LIST_APPS_CLOUD_FIELDS = listOf("mCloudWhiteList", "USE_CLOUD_WHITE_LIST")
    private val LIST_APPS_DATA_WHITE_FIELDS = listOf("mUseDataWhiteList", "USE_DATA_WHITE_LIST")
    private val LIST_APPS_BUILTIN_WHITE_FIELDS = listOf(
        "mDataWhiteList",
        "DATA_WHITE_LIST",
        "mMiDataWhiteList",
        "MI_DATA_WHITE_LIST",
        "mSleepModeList",
        "SLEEP_MODE_LIST",
        "mSystemWhiteList",
        "SYSTEM_WHITE_LIST"
    )
    private val LIST_APPS_SYSTEM_BLACK_FIELDS = listOf(
        "mSystemBlackList",
        "SYSTEM_BLACK_LIST"
    )
    private val LIST_APPS_EXTRA_WHITE_FIELDS = listOf(
        "mAudioBlackList",
        "AUDIO_BLACK_LIST"
    )

    @Volatile
    var listAppsManagerClass: Class<*>? = null

    @Volatile
    private var lastCloudFrozenWhiteListRaw: String? = null

    private val cloudFrozenWhiteListLock = Any()
    private val cachedCloudFrozenEntries = LinkedHashSet<String>()

    /** 记录 S32 捕获到的 OS3/OS4 `ListAppsManager` 类与最近一次云墓碑白名单原始串。 */
    fun recordCloudFrozenWhiteListRaw(clazz: Class<*>?, values: String?) {
        if (clazz != null) listAppsManagerClass = clazz
        if (!values.isNullOrBlank()) {
            lastCloudFrozenWhiteListRaw = values
        }
    }

    /** 仅供单测清理静态缓存。 */
    internal fun resetCloudFrozenWhiteListCacheForTest() {
        synchronized(cloudFrozenWhiteListLock) {
            listAppsManagerClass = null
            lastCloudFrozenWhiteListRaw = null
            cachedCloudFrozenEntries.clear()
        }
    }

    /**
     * 热同步 OS3/OS4 `ListAppsManager` 的云端墓碑白名单。
     *
     * OS3 字段为 `mCloudWhiteList` / `mUseDataWhiteList`，OS4 字段为 `USE_CLOUD_WHITE_LIST` /
     * `USE_DATA_WHITE_LIST`，均通过能力探测动态定位；旧 ROM 不存在该类时静默跳过。
     */
    fun syncCloudFrozenWhiteListToListAppsManager(block: Boolean, classLoader: ClassLoader? = null) {
        val loader = classLoader
            ?: listAppsManagerClass?.classLoader
            ?: greezeService?.javaClass?.classLoader
            ?: aurogonService?.javaClass?.classLoader
            ?: SystemState::class.java.classLoader
        val clazz = listAppsManagerClass
            ?: XReflect.findClassIfExists(CLASS_LIST_APPS_MANAGER, loader)?.also { listAppsManagerClass = it }
            ?: return
        val userWhite = AppUidUtils.policyPackagesForUser(
            PrefReader.systemPrefs.whiteAppSet,
            AppUidUtils.PRIMARY_USER_ID
        )
        try {
            val changed = syncListAppsManagerClass(clazz, block, userWhite)
            if (changed > 0) {
                if (block) {
                    Logger.alwaysInfo("系统侧云墓碑白名单已热屏蔽（移除 $changed 项）")
                } else {
                    Logger.alwaysInfo("系统侧云墓碑白名单已热恢复（恢复 $changed 项）")
                }
            }
        } catch (t: Throwable) {
            Logger.error("热同步 ListAppsManager 云墓碑白名单失败", t)
        }
    }

    @Suppress("UNCHECKED_CAST")
    internal fun syncListAppsManagerClass(
        clazz: Class<*>,
        block: Boolean,
        userWhitePackages: Set<String> = emptySet(),
    ): Int {
        synchronized(cloudFrozenWhiteListLock) {
            val cloudSet = resolveStaticMutableSet(clazz, LIST_APPS_CLOUD_FIELDS)
            val dataWhiteSet = resolveStaticMutableSet(clazz, LIST_APPS_DATA_WHITE_FIELDS)
                ?: resolveUseDataWhiteSetByMethod(clazz)

            if (block) {
                if (cloudSet == null || cloudSet.isEmpty()) return 0
                val snapshot = ArrayList(cloudSet)
                cachedCloudFrozenEntries.addAll(snapshot)
                cloudSet.clear()

                val protectedEntries = LinkedHashSet<String>().apply {
                    add(VOICE_ASSIST_PACKAGE)
                    addAll(userWhitePackages)
                    for (fieldName in LIST_APPS_BUILTIN_WHITE_FIELDS) {
                        if (!RomCapabilities.hasField(clazz, fieldName)) continue
                        val raw = runCatching { XReflect.getStaticObjectField(clazz, fieldName) }.getOrNull()
                        if (raw is Collection<*>) {
                            for (item in raw) {
                                if (item is String && item.isNotEmpty()) add(item)
                            }
                        }
                    }
                }
                if (dataWhiteSet != null) {
                    for (pkg in snapshot) {
                        if (pkg !in protectedEntries) {
                            dataWhiteSet.remove(pkg)
                        }
                    }
                }
                return snapshot.size
            } else {
                val raw = lastCloudFrozenWhiteListRaw
                if (!raw.isNullOrBlank() && RomCapabilities.hasMethod(clazz, "dealCloudWhiteList")) {
                    val beforeSize = cloudSet?.size ?: 0
                    val invoked = runCatching {
                        XReflect.callStaticMethod(clazz, "dealCloudWhiteList", raw)
                    }.isSuccess
                    if (invoked) {
                        val afterSize = cloudSet?.size ?: 0
                        return if (cloudSet != null) (afterSize - beforeSize).coerceAtLeast(0) else 1
                    }
                }
                if (cachedCloudFrozenEntries.isEmpty()) return 0
                var restored = 0
                for (pkg in cachedCloudFrozenEntries) {
                    var touched = false
                    if (cloudSet != null && cloudSet.add(pkg)) touched = true
                    if (dataWhiteSet != null && dataWhiteSet.add(pkg)) touched = true
                    if (touched) restored++
                }
                return restored
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun resolveStaticMutableSet(clazz: Class<*>, fieldNames: List<String>): MutableSet<String>? {
        for (fieldName in fieldNames) {
            if (!RomCapabilities.hasField(clazz, fieldName)) continue
            val value = runCatching { XReflect.getStaticObjectField(clazz, fieldName) }.getOrNull()
            if (value is MutableSet<*>) {
                return value as MutableSet<String>
            }
        }
        return null
    }

    @Suppress("UNCHECKED_CAST")
    private fun resolveUseDataWhiteSetByMethod(clazz: Class<*>): MutableSet<String>? {
        if (!RomCapabilities.hasMethod(clazz, "getUseDataWhiteList")) return null
        val value = runCatching { XReflect.callStaticMethod(clazz, "getUseDataWhiteList") }.getOrNull()
        return value as? MutableSet<String>
    }

    /**
     * `ServiceManager.getService("deviceidle")` 返回的是 `DeviceIdleController$BinderService`，
     * 而 Doze 状态机字段（mForceIdle / mState / mScreenOn）都在外层 `DeviceIdleController` 上。
     * 找到持有目标类型的合成外指字段时解一层，避免句柄类型错位让 Doze 控制整体失效。
     */
    private fun unwrapServiceImplementation(service: Any?, expectedTypeName: String): Any? {
        if (service == null) return null
        if (service.javaClass.name == expectedTypeName) return service
        val expectedType = runCatching {
            Class.forName(expectedTypeName, false, service.javaClass.classLoader)
        }.getOrNull() ?: return service
        for (field in service.javaClass.declaredFields) {
            if (java.lang.reflect.Modifier.isStatic(field.modifiers)) continue
            if (!expectedType.isAssignableFrom(field.type)) continue
            val value = runCatching {
                field.isAccessible = true
                field.get(service)
            }.getOrNull()
            if (value != null) return value
        }
        return service
    }

    private fun resolveSystemService(name: String): Any? = try {
        Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
            .invoke(null, name)
    } catch (_: Throwable) {
        null
    }

    /** 在 [owner] 的字段或零参方法里找类型为 [typeName] 的实例。 */
    private fun findInstanceOfType(owner: Any, typeName: String): Any? {
        val type = runCatching {
            Class.forName(typeName, false, owner.javaClass.classLoader)
        }.getOrNull() ?: return null
        for (field in owner.javaClass.declaredFields) {
            if (!type.isAssignableFrom(field.type)) continue
            val value = runCatching {
                field.isAccessible = true
                field.get(owner)
            }.getOrNull()
            if (value != null) return value
        }
        for (method in owner.javaClass.methods) {
            if (method.parameterCount != 0 || !type.isAssignableFrom(method.returnType)) continue
            val value = runCatching { method.invoke(owner) }.getOrNull()
            if (value != null) return value
        }
        return null
    }

    fun clearAllPidFreezeRecords() {
        pidFreezeLock.lock()
        try {
            uidPidFreezeTable.clear()
            recentTombstoneReasons.clear()
            Logger.info("清除冻结记录")
        } finally {
            pidFreezeLock.unlock()
        }
    }
}
