package io.github.univers629.millet.powerkeeper

import android.content.Context
import android.content.pm.ApplicationInfo
import io.github.univers629.millet.core.UidPackageCache
import io.github.univers629.millet.core.ActiveImeTracker
import io.github.univers629.millet.core.AppUidUtils
import io.github.univers629.millet.core.HookDecisions
import io.github.univers629.millet.core.XReflect
import io.github.univers629.millet.core.Logger
import io.github.univers629.millet.core.PrefReader
import io.github.univers629.millet.core.PrefsSnapshot
import io.github.univers629.millet.core.RomCapabilities
import io.github.univers629.millet.system.BatteryPolicyApplier
import java.lang.ref.WeakReference

/**
 * `com.miui.powerkeeper` 作用域全局状态、反射对象句柄池与 `MilletPolicy` 内部名单改写器。
 *
 * 修正缺陷 #3（Union 合并而非全表覆盖）：
 * 原模块在 `applyMessageAndWhiteLists()` 中使用 `setObjectField` 将 `mUseSystemBlackList`
 * 直接替换为 `new HashSet(forceFreezeSet)`，将 `mUseDataWhiteList` 直接替换为 `new HashSet(whiteAppSet)`：
 * - 当 `forceFreezeSet` 为空时，MIUI 构造器预置的 13 项内置黑名单（`mSystemBlackList`）被全部清空；
 * - 当 `whiteAppSet` 为空时，MIUI 构造器预置的约 40 项内置白名单（`mDataWhiteList + mMiDataWhiteList`）被全部清空。
 * 本实现首次访问时先快照保存 MIUI 原生内置集合，随后每次更新均在原生内置集合基础上执行 **union（并集）** 与差集运算。
 */
object PowerKeeperState {

    private const val CLASS_BRIGHT_SCENARIO_BASE = "com.miui.powerkeeper.millet.bright.BrightScenarioBase"

    /** PowerKeeper 类加载器 */
    @Volatile
    var appClassLoader: ClassLoader? = null

    /** P1 捕获的 PowerKeeperApplication Context（弱引用，避免持有 Context 强引用） */
    @Volatile
    private var applicationContextRef: WeakReference<Context>? = null

    var applicationContext: Context?
        get() = applicationContextRef?.get()
        set(value) {
            applicationContextRef = if (value != null) WeakReference(value) else null
        }

    /**
     * 热重载后重新取回 Context。
     *
     * P1 只在开机时捕获一次 Application，热重载后新一代的弱引用是空的；
     * powerkeeper 进程里的 `ActivityThread.currentApplication()` 任何时候都有效，
     * 用它补回即可，避免重载后的 Hook 因为拿不到 Context 而静默失效。
     */
    fun reacquireApplicationContext(): Context? {
        if (applicationContext == null) {
            val ctx = runCatching {
                Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication")
                    .invoke(null)
            }.getOrNull() as? Context
            if (ctx != null) applicationContext = ctx
        }
        return applicationContext
    }

    /** P2 捕获的 com.miui.powerkeeper.millet.MilletPolicy 实例 */
    @Volatile
    var milletPolicyInstance: Any? = null

    /** P18 捕获的 com.miui.powerkeeper.PowerKeeperManager 实例 */
    @Volatile
    var powerKeeperManagerInstance: Any? = null

    /** P20 捕获的 com.miui.powerkeeper.millet.bright.BrightScenarioBase 实例 */
    @Volatile
    var brightScenarioInstance: Any? = null

    /** 热重载后恢复上一代携带过来的 PowerKeeper 宿主对象句柄（均为宿主类加载器实例）。 */
    fun restoreHostHandles(snapshot: Array<*>?) {
        if (snapshot == null) return
        if (milletPolicyInstance == null) milletPolicyInstance = snapshot.getOrNull(0)
        if (powerKeeperManagerInstance == null) powerKeeperManagerInstance = snapshot.getOrNull(1)
        if (brightScenarioInstance == null) brightScenarioInstance = snapshot.getOrNull(2)
    }

    /** 模块定义的亮屏白名单基础集合（4 项） */
    private val BASE_BRIGHT_WHITE_APPS: Set<String> = linkedSetOf(
        "com.xiaomi.smarthome",
        "com.android.vending",
        "com.google.android.gms",
        "com.miui.gallery"
    )

    /** 模块定义的预加载白名单基础集合（5 项，用于 P20 BrightScenarioBase.preloadWhiteApps） */
    private val BASE_PRELOAD_WHITE_APPS: List<String> = listOf(
        "com.miui.newhome",
        "com.mfashiongallery.emag",
        "com.miui.mediaeditor",
        "com.miui.weather2",
        "com.xiaomi.scanner"
    )

    /** 从 MIUI 静态 `whiteApps` 中剔除的 2 个系统广告/应用商店包名 */
    private val REMOVED_STATIC_WHITE_APPS: Set<String> = setOf(
        "com.miui.systemAdSolution",
        "com.xiaomi.market"
    )

    /** 从 MIUI 静态 `mDataWhiteList` 中剔除的 11 个内置阅读器/钱包/企业 IM 包名 */
    private val REMOVED_DATA_WHITE_APPS: Set<String> = setOf(
        "com.miui.notes",
        "com.flyersoft.moonreaderp",
        "com.sinovoice.voicebook",
        "com.wyfc.itingtxt2",
        "com.gedoor.monkeybook",
        "com.iflytek.vflynote",
        "com.flyersoft.seekbooks",
        "com.flyersoft.moonreader",
        "com.miui.themestore",
        "com.mipay.wallet",
        "com.tencent.wework"
    )

    /** MIUI 原生内置系统黑名单快照（13 项，用于 union 合并） */
    @Volatile
    private var miuiBuiltInSystemBlackSet: Set<String>? = null

    /** MIUI 原生内置数据白名单快照（用于 union 合并） */
    @Volatile
    private var miuiBuiltInDataWhiteSet: Set<String>? = null

    private class ListProjection {
        val removed = LinkedHashSet<String>()
        val added = LinkedHashSet<String>()
    }

    private val listProjections = java.util.IdentityHashMap<MutableCollection<String>, ListProjection>()

    /** 包名表无法隔离共享 UID 或双开；查询不完整时保留原生豁免。 */
    private fun canRestrictPackage(packageName: String, prefs: PrefsSnapshot): Boolean {
        val ctx = applicationContext ?: return false
        val token = android.os.Binder.clearCallingIdentity()
        return try {
            @Suppress("DEPRECATION")
            val info = ctx.packageManager.getApplicationInfo(packageName, 0)
            val uid = info.uid
            if (!AppUidUtils.isManagedAppUid(uid) || AppUidUtils.userIdOf(uid) != AppUidUtils.PRIMARY_USER_ID ||
                info.flags and ApplicationInfo.FLAG_SYSTEM != 0 ||
                AppUidUtils.containsPolicyTarget(prefs.whiteAppSet, packageName, uid) ||
                ActiveImeTracker.isProtectedSystemServiceApp(ctx, packageName, prefs, uid)
            ) return false
            val uidPackages = ctx.packageManager.getPackagesForUid(uid) ?: return false
            if (uidPackages.size != 1 || uidPackages[0] != packageName) return false
            val dualUid = AppUidUtils.dualUidOf(uid)
            if (dualUid > 0 && ctx.packageManager.getPackagesForUid(dualUid)?.contains(packageName) == true) return false
            // 钩子线程可能持有平台锁，前台可见性只读异步快照；未知时保守保留原生豁免。
            val visible = io.github.univers629.millet.system.ForegroundState.isUidVisible(uid) ?: return false
            !visible
        } catch (_: Throwable) {
            false
        } finally {
            android.os.Binder.restoreCallingIdentity(token)
        }
    }

    /** 只回收本次实际增删项，不替换云端或构造器保留的其它表项。 */
    private fun syncListProjection(
        current: MutableCollection<String>,
        additions: Set<String>,
        removals: Set<String>,
    ) {
        synchronized(listProjections) {
            val state = listProjections.getOrPut(current) { ListProjection() }
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
     * 清空 `MilletPolicy.musicApp` 静态列表（P2 构造器调用）。
     */
    @Suppress("UNCHECKED_CAST")
    fun clearMusicApps(policy: Any) {
        try {
            val musicList = XReflect.getStaticObjectField(policy.javaClass, "musicApp") as? MutableList<String>
            musicList?.clear()
        } catch (t: Throwable) {
            Logger.error("设置音乐应用失败", t)
        }
    }

    /**
     * 将模块配置的 `messageAppSet`、`forceFreezeSet`、`whiteAppSet` 写入 `MilletPolicy` 的 6 张内部表。
     * 采用 **union 合并策略**保留 MIUI 原生内置表项（修正缺陷 #3）。
     */
    @Suppress("UNCHECKED_CAST")
    fun applyMessageAndWhiteLists() {
        val policy = milletPolicyInstance ?: return
        val policyClass = policy.javaClass
        val prefs = PrefReader.powerKeeperPrefs
        // ROM 包名表只投影主用户策略，不写入双开目标。
        val forceFreezeApps = AppUidUtils.policyPackagesForUser(prefs.forceFreezeSet, AppUidUtils.PRIMARY_USER_ID)
            .filterTo(LinkedHashSet()) { canRestrictPackage(it, prefs) }
        val whiteApps = AppUidUtils.policyPackagesForUser(prefs.whiteAppSet, AppUidUtils.PRIMARY_USER_ID)
        val suppressMessageApps = LinkedHashSet(forceFreezeApps).apply {
            addAll(AppUidUtils.policyPackagesForUser(prefs.blockThawSet, AppUidUtils.PRIMARY_USER_ID))
            addAll(AppUidUtils.policyPackagesForUser(prefs.ignoreNetworkWakeSet, AppUidUtils.PRIMARY_USER_ID))
        }.filterTo(LinkedHashSet()) { canRestrictPackage(it, prefs) }
        val messageApps = AppUidUtils.policyPackagesForUser(prefs.messageAppSet, AppUidUtils.PRIMARY_USER_ID)
            .filterTo(LinkedHashSet()) { it !in suppressMessageApps }
        val staticRemovals = REMOVED_STATIC_WHITE_APPS.filterTo(LinkedHashSet()) { canRestrictPackage(it, prefs) }
        val dataRemovals = REMOVED_DATA_WHITE_APPS.filterTo(LinkedHashSet()) { canRestrictPackage(it, prefs) }
        val builtInBlack = captureOrGetBuiltInSystemBlackSet(policy)
        val builtInWhite = captureOrGetBuiltInDataWhiteSet(policy)

        // 1. MESSAGE_APPS（静态 List<String>）：合并用户配置的消息应用集合
        try {
            val messageList = XReflect.getStaticObjectField(policyClass, "MESSAGE_APPS") as? MutableList<String>
            if (messageList != null) {
                syncListProjection(messageList, messageApps, suppressMessageApps)
                if (prefs.isLog) {
                    Logger.warn("设置消息应用:$messageList")
                }
            }
        } catch (t: Throwable) {
            Logger.error("设置消息应用:${t.message}")
        }

        // 2. BRIGHT_WHITE_APPS（静态 List<String>）：基础 4 项剔除强制冻结应用（同时兼容 MilletPolicy 与 BrightScenarioBase）
        //    该字段只在 Android 14 / 澎湃 2.0（PowerKeeper 5.x）及以上存在，
        //    Android 13 / 澎湃 1.0（PowerKeeper 4.2.00）没有它，直接跳过而不是抛异常。
        try {
            val mergedBright = LinkedHashSet(BASE_BRIGHT_WHITE_APPS)
            mergedBright.removeAll(forceFreezeApps)

            if (RomCapabilities.hasField(policyClass, "BRIGHT_WHITE_APPS")) {
                val brightList = XReflect.getStaticObjectField(policyClass, "BRIGHT_WHITE_APPS") as? MutableList<String>
                if (brightList != null) {
                    syncListProjection(brightList, mergedBright, forceFreezeApps)
                    if (prefs.isLog) {
                        Logger.warn("系统内置亮屏白名单:$brightList")
                    }
                }
            }

            val loader = appClassLoader ?: policyClass.classLoader
            val brightScenarioClass = if (loader != null) {
                XReflect.findClassIfExists(CLASS_BRIGHT_SCENARIO_BASE, loader)
            } else {
                null
            }
            if (brightScenarioClass != null && RomCapabilities.hasField(brightScenarioClass, "BRIGHT_WHITE_APPS")) {
                try {
                    val scenarioBrightList = XReflect.getStaticObjectField(
                        brightScenarioClass,
                        "BRIGHT_WHITE_APPS"
                    ) as? MutableList<String>
                    if (scenarioBrightList != null) {
                        syncListProjection(scenarioBrightList, mergedBright, forceFreezeApps)
                    }
                } catch (_: Throwable) {
                }
            }
        } catch (t: Throwable) {
            Logger.error("系统内置亮屏白名单:${t.message}")
        }

        // 3. whiteApps（静态 List<String>）：保留安全豁免，记录实际移除项以便撤回策略。
        try {
            val staticWhiteList = XReflect.getStaticObjectField(policyClass, "whiteApps") as? MutableList<String>
            if (staticWhiteList != null) {
                syncListProjection(staticWhiteList, emptySet(), staticRemovals + forceFreezeApps)
                if (prefs.isLog) {
                    Logger.warn("设置白名单:$staticWhiteList")
                }
            }
        } catch (t: Throwable) {
            Logger.error("设置白名单失败:${t.message}")
        }

        // 4. mDataWhiteList（静态 List<String>）：仅剔除非安全策略目标。
        try {
            val dataWhiteList = XReflect.getStaticObjectField(policyClass, "mDataWhiteList") as? MutableList<String>
            if (dataWhiteList != null) {
                syncListProjection(dataWhiteList, emptySet(), dataRemovals + forceFreezeApps)
                if (prefs.isLog) {
                    Logger.warn("设置白名单:$dataWhiteList")
                }
            }
        } catch (t: Throwable) {
            Logger.error("设置用户白名单失败:${t.message}")
        }

        // 5. mUseSystemBlackList（实例 Set<String>）：MIUI 内置黑名单 ∪ forceFreezeSet \ whiteAppSet（修正缺陷 #3）
        try {
            val unionBlack = LinkedHashSet<String>(builtInBlack)
            unionBlack.addAll(forceFreezeApps)
            unionBlack.removeAll(whiteApps)

            val currentSet = XReflect.getObjectField(policy, "mUseSystemBlackList") as? MutableSet<String>
            if (currentSet != null) {
                syncListProjection(currentSet, unionBlack, whiteApps)
            }
            if (prefs.isLog) {
                Logger.warn("内置黑名单:${XReflect.getObjectField(policy, "mUseSystemBlackList")}")
            }
        } catch (t: Throwable) {
            Logger.error("设置内置黑名单失败:${t.message}")
        }

        // 6. mUseDataWhiteList（实例 Set<String>）：MIUI 内置数据白名单 ∪ whiteAppSet \ forceFreezeSet（修正缺陷 #3）
        try {
            val unionWhite = LinkedHashSet<String>(builtInWhite)
            unionWhite.removeAll(dataRemovals)
            unionWhite.removeAll(forceFreezeApps)
            unionWhite.addAll(whiteApps)

            val currentSet = XReflect.getObjectField(policy, "mUseDataWhiteList") as? MutableSet<String>
            if (currentSet != null) {
                syncListProjection(currentSet, unionWhite, dataRemovals + forceFreezeApps)
            }
            if (prefs.isLog) {
                Logger.warn("内置用户白名单:${XReflect.getObjectField(policy, "mUseDataWhiteList")}")
            }
        } catch (t: Throwable) {
            Logger.error("设置内置用户白名单失败:${t.message}")
        }
    }

    /**
     * P20：增量同步 `BrightScenarioBase.preloadWhiteApps` 的非安全强制冻结差集。
     */
    @Suppress("UNCHECKED_CAST")
    fun applyPreloadWhiteApps() {
        val loader = appClassLoader ?: brightScenarioInstance?.javaClass?.classLoader ?: return
        try {
            val clazz = XReflect.findClassIfExists(CLASS_BRIGHT_SCENARIO_BASE, loader) ?: return
            val prefs = PrefReader.powerKeeperPrefs
            val forceFreezeApps = AppUidUtils.policyPackagesForUser(prefs.forceFreezeSet, AppUidUtils.PRIMARY_USER_ID)
                .filterTo(LinkedHashSet()) { canRestrictPackage(it, prefs) }
            val current = XReflect.getStaticObjectField(clazz, "preloadWhiteApps") as? MutableList<String> ?: return
            syncListProjection(current, BASE_PRELOAD_WHITE_APPS.toSet() - forceFreezeApps, forceFreezeApps)
        } catch (t: Throwable) {
            Logger.error("更新 preloadWhiteApps 失败:${t.message}")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun captureOrGetBuiltInSystemBlackSet(policy: Any): Set<String> {
        miuiBuiltInSystemBlackSet?.let { return it }
        val captured = LinkedHashSet<String>()
        var instanceReady = false
        try {
            val existing = XReflect.getObjectField(policy, "mUseSystemBlackList") as? Collection<String>
            if (existing != null) {
                captured.addAll(existing)
                instanceReady = true
            }
        } catch (_: Throwable) {
        }
        try {
            val staticBlack = XReflect.getStaticObjectField(policy.javaClass, "mSystemBlackList") as? Collection<String>
            if (staticBlack != null) captured.addAll(staticBlack)
        } catch (_: Throwable) {
        }
        if (instanceReady) {
            miuiBuiltInSystemBlackSet = captured
        }
        return captured
    }

    @Suppress("UNCHECKED_CAST")
    private fun captureOrGetBuiltInDataWhiteSet(policy: Any): Set<String> {
        miuiBuiltInDataWhiteSet?.let { return it }
        val captured = LinkedHashSet<String>()
        var instanceReady = false
        val cloudSnapshot = try {
            if (RomCapabilities.hasField(policy.javaClass, "mCloudWhiteList")) {
                (XReflect.getObjectField(policy, "mCloudWhiteList") as? Collection<String>)?.toSet().orEmpty()
            } else {
                emptySet()
            }
        } catch (_: Throwable) {
            emptySet()
        }
        try {
            val existing = XReflect.getObjectField(policy, "mUseDataWhiteList") as? Collection<String>
            if (existing != null) {
                for (item in existing) {
                    if (item !in cloudSnapshot) captured.add(item)
                }
                instanceReady = true
            }
        } catch (_: Throwable) {
        }
        try {
            val staticData = XReflect.getStaticObjectField(policy.javaClass, "mDataWhiteList") as? Collection<String>
            if (staticData != null) captured.addAll(staticData)
        } catch (_: Throwable) {
        }
        try {
            val staticMiData = XReflect.getStaticObjectField(policy.javaClass, "mMiDataWhiteList") as? Collection<String>
            if (staticMiData != null) captured.addAll(staticMiData)
        } catch (_: Throwable) {
        }
        if (instanceReady) {
            miuiBuiltInDataWhiteSet = captured
        }
        return captured
    }

    private fun resolveMilletPolicyInstance(): Any? {
        milletPolicyInstance?.let { return it }
        val loader = appClassLoader ?: applicationContext?.classLoader ?: return null
        val clazz = XReflect.findClassIfExists("com.miui.powerkeeper.millet.MilletPolicy", loader) ?: return null
        for (field in clazz.declaredFields) {
            if (!java.lang.reflect.Modifier.isStatic(field.modifiers)) continue
            if (!clazz.isAssignableFrom(field.type)) continue
            val instance = runCatching {
                field.isAccessible = true
                field.get(null)
            }.getOrNull()
            if (instance != null) {
                milletPolicyInstance = instance
                return instance
            }
        }
        return null
    }

    /**
     * 热同步 OS1/OS2 `MilletPolicy` 的云墓碑白名单（`mCloudWhiteList` / `mUseDataWhiteList`）。
     *
     * - `block = true`：先快照内置白名单，再清空 `mCloudWhiteList` 并从 `mUseDataWhiteList` 移除其条目，最后重放内置与用户白名单；
     * - `block = false`：重新调用 `dealCloudWhiteList()` 载入官方云名单，再重放用户策略保持强制冻结优先级。
     */
    @Suppress("UNCHECKED_CAST")
    fun syncCloudFrozenWhiteList(block: Boolean) {
        val policy = resolveMilletPolicyInstance() ?: return
        val clazz = policy.javaClass
        try {
            if (block) {
                captureOrGetBuiltInDataWhiteSet(policy)
                val cloudSet = if (RomCapabilities.hasField(clazz, "mCloudWhiteList")) {
                    XReflect.getObjectField(policy, "mCloudWhiteList") as? MutableSet<String>
                } else {
                    null
                }
                val useWhiteSet = if (RomCapabilities.hasField(clazz, "mUseDataWhiteList")) {
                    XReflect.getObjectField(policy, "mUseDataWhiteList") as? MutableSet<String>
                } else {
                    null
                }
                if (cloudSet != null && cloudSet.isNotEmpty()) {
                    val removed = ArrayList(cloudSet)
                    cloudSet.clear()
                    useWhiteSet?.removeAll(removed)
                    Logger.alwaysInfo("云墓碑白名单已热屏蔽（移除 ${removed.size} 项）")
                }
                applyMessageAndWhiteLists()
            } else {
                if (RomCapabilities.hasMethod(clazz, "dealCloudWhiteList")) {
                    XReflect.callMethod(policy, "dealCloudWhiteList")
                    Logger.alwaysInfo("云墓碑白名单已热恢复")
                }
                applyMessageAndWhiteLists()
            }
        } catch (t: Throwable) {
            Logger.error("热同步云墓碑白名单失败", t)
        }
    }

    // ==================== 系统设置手动省电策略短时租约（P31/P32 ↔ P7/P8） ====================

    /**
     * 用户在系统设置「省电策略」手动修改应用策略后的短时放行租约（15 秒）。
     *
     * HyperOS 写入 `PowerSaveConfigureManager#setPowerSaveAppConfigure` 后，
     * PowerKeeper 会在约 1 秒后经 `CommonAdapter#add/removePowerSaveWhitelistApps`
     * 同步 Doze 白名单；若被 P7/P8 拦截，下次进页面时 `getPowerSaveAppConfigure` 会因为
     * `isIgnoringBatteryOptimizations == false` 将「无限制」强行回退为「智能推荐」。
     */
    internal const val USER_POWER_SAVE_LEASE_MS = 15_000L

    private data class PowerSaveLease(val expectAdd: Boolean, val expireAtMs: Long)

    private val userPowerSaveLeases = java.util.concurrent.ConcurrentHashMap<String, PowerSaveLease>()
    private val inGetPowerSaveConfigureDepth = object : ThreadLocal<Int>() {
        override fun initialValue(): Int = 0
    }

    fun enterGetPowerSaveConfigure() {
        val current = inGetPowerSaveConfigureDepth.get() ?: 0
        inGetPowerSaveConfigureDepth.set(current + 1)
    }

    fun exitGetPowerSaveConfigure() {
        val current = inGetPowerSaveConfigureDepth.get() ?: 0
        if (current <= 1) inGetPowerSaveConfigureDepth.remove()
        else inGetPowerSaveConfigureDepth.set(current - 1)
    }

    fun isInsideGetPowerSaveConfigure(): Boolean = (inGetPowerSaveConfigureDepth.get() ?: 0) > 0

    fun recordUserPowerSaveIntent(
        packageName: String?,
        expectAdd: Boolean,
        nowMs: Long = android.os.SystemClock.elapsedRealtime(),
    ) {
        if (packageName.isNullOrBlank()) return
        if (isInsideGetPowerSaveConfigure()) return
        pruneExpiredPowerSaveLeases(nowMs)
        userPowerSaveLeases[packageName] = PowerSaveLease(
            expectAdd = expectAdd,
            expireAtMs = nowMs + USER_POWER_SAVE_LEASE_MS,
        )
    }

    /** 按短时租约 + 配置库真值过滤 `CommonAdapter#add/removePowerSaveWhitelistApps` 的包名数组。 */
    internal fun filterPowerSaveWhitelistMutation(
        packages: Array<String>,
        isAdd: Boolean,
        nowMs: Long = android.os.SystemClock.elapsedRealtime(),
    ): Pair<List<String>, List<String>> {
        val allowed = ArrayList<String>()
        val blocked = ArrayList<String>()
        val unmatched = ArrayList<String>()
        for (pkg in packages) {
            val lease = userPowerSaveLeases[pkg]
            if (lease != null && lease.expireAtMs >= nowMs && lease.expectAdd == isAdd) {
                allowed.add(pkg)
            } else {
                if (lease != null && lease.expireAtMs < nowMs) {
                    userPowerSaveLeases.remove(pkg, lease)
                }
                unmatched.add(pkg)
            }
        }
        if (unmatched.isEmpty()) return allowed to blocked
        // 租约只覆盖「设置后约 1 秒」的那一次同步；PowerKeeper 后续重建名单（开机、云策略更新）
        // 早已超出租约。此时以配置库的 bgControl 为准：库里是「无限制」就放行添加、不是就放行移除，
        // 避免用户手动选「无限制」后被自动同步改回「智能推荐」。
        val bgControls = BatteryPolicyApplier.readBgControls(unmatched)
        for (pkg in unmatched) {
            val unrestricted = bgControls[pkg]?.let { it == BatteryPolicyApplier.BG_NO_RESTRICT }
            if (HookDecisions.allowPowerSaveMutation(isAdd = isAdd, unrestrictedInDatabase = unrestricted)) {
                allowed.add(pkg)
            } else {
                blocked.add(pkg)
            }
        }
        return allowed to blocked
    }

    private fun pruneExpiredPowerSaveLeases(nowMs: Long) {
        if (userPowerSaveLeases.size < 16) return
        val it = userPowerSaveLeases.entries.iterator()
        while (it.hasNext()) {
            if (it.next().value.expireAtMs < nowMs) it.remove()
        }
    }

    internal fun clearUserPowerSaveLeasesForTest() {
        userPowerSaveLeases.clear()
        inGetPowerSaveConfigureDepth.remove()
    }

    // ==================== UID / 包名查询辅助方法 ====================

    fun getPackageNameForUid(uid: Int): String? {
        if (uid <= 0) return null
        // 钩子线程可能持有平台锁，只读缓存；未命中时后台解析。
        UidPackageCache.lookup(uid)?.let { return it }
        UidPackageCache.requestResolve(uid, applicationContext)
        return null
    }

    fun syncDualAppSupport(enabled: Boolean) {
        val loader = appClassLoader
            ?: milletPolicyInstance?.javaClass?.classLoader
            ?: powerKeeperManagerInstance?.javaClass?.classLoader
            ?: return
        val configClass = XReflect.findClassIfExists("com.miui.powerkeeper.millet.MilletConfig", loader) ?: return
        for (fieldName in listOf("supportDoubleApp", "mSupportDoubleApp")) {
            if (io.github.univers629.millet.core.RomCapabilities.hasField(configClass, fieldName)) {
                runCatching { XReflect.findField(configClass, fieldName).setBoolean(null, enabled) }
            }
        }
        if (enabled && io.github.univers629.millet.core.RomCapabilities.hasField(configClass, "ENABLE_DOUBLE_APP")) {
            runCatching {
                val raw = XReflect.getStaticObjectField(configClass, "ENABLE_DOUBLE_APP") as? Collection<*>
                val device = android.os.Build.DEVICE
                if (raw != null && device !in raw) {
                    val updated = ArrayList<String>(raw.size + 1).apply {
                        for (item in raw) {
                            if (item is String) add(item)
                        }
                        add(device)
                    }
                    XReflect.setStaticObjectField(configClass, "ENABLE_DOUBLE_APP", updated)
                }
            }
        }
    }

    @Volatile
    private var originalFirstScreenOffDelayMs: Int? = null

    @Volatile
    private var originalBrightDelayMs: Int? = null

    /**
     * OS1/OS2 息屏 62s 延迟由 `MilletConfig.firstScreenOffDelayTime`（默认 62,000ms）与
     * `BrightController.DELAY_TIME` 静态字段驱动；开启后可逆地将其钳位至 5,000ms，关闭时恢复原值。
     */
    fun syncFastScreenOffFreeze(enabled: Boolean = true, context: Context? = applicationContext) {
        val loader = appClassLoader
            ?: milletPolicyInstance?.javaClass?.classLoader
            ?: powerKeeperManagerInstance?.javaClass?.classLoader
            ?: brightScenarioInstance?.javaClass?.classLoader
            ?: context?.classLoader
            ?: return

        val configClass = XReflect.findClassIfExists("com.miui.powerkeeper.millet.MilletConfig", loader)
        if (configClass != null && RomCapabilities.hasField(configClass, "firstScreenOffDelayTime")) {
            runCatching {
                val field = XReflect.findField(configClass, "firstScreenOffDelayTime")
                val current = field.getInt(null)
                if (originalFirstScreenOffDelayMs == null) {
                    originalFirstScreenOffDelayMs = current
                }
                val base = originalFirstScreenOffDelayMs ?: current
                val target = HookDecisions.clampScreenOffFreezeDelayMs(base, enabled)
                if (current != target) {
                    field.setInt(null, target)
                    Logger.alwaysInfo("已同步 MilletConfig.firstScreenOffDelayTime: ${current}ms -> ${target}ms")
                }
            }
        }

        val brightClass = XReflect.findClassIfExists("com.miui.powerkeeper.millet.bright.BrightController", loader)
        if (brightClass != null && RomCapabilities.hasField(brightClass, "DELAY_TIME")) {
            runCatching {
                val field = XReflect.findField(brightClass, "DELAY_TIME")
                val current = field.getInt(null)
                if (originalBrightDelayMs == null) {
                    originalBrightDelayMs = current
                }
                val base = originalBrightDelayMs ?: current
                val target = HookDecisions.clampScreenOffFreezeDelayMs(base, enabled)
                if (current != target && base > 0) {
                    field.setInt(null, target)
                    Logger.alwaysInfo("已同步 BrightController.DELAY_TIME: ${current}ms -> ${target}ms")
                }
            }
        }
    }
}
