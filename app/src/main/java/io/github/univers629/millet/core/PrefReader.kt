package io.github.univers629.millet.core

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.api.XposedModule

/**
 * 配置读取、缓存刷新与 UI 写盘热更新器。
 *
 * 1. Hook 侧通过 `XposedModule#getRemotePreferences("Settings")` 读取配置。
 * 2. 分别维护 `system_server` 与 `com.miui.powerkeeper` 两个进程的配置快照，支持按 `PrefGroup` 增量重载。
 * 3. UI 侧使用 Device-Protected Storage 下的 `Settings.xml`，优先以 `MODE_WORLD_READABLE | MODE_MULTI_PROCESS` 打开，
 *    失败时自动回退至 `MODE_MULTI_PROCESS` / `MODE_PRIVATE`，并在每次写入后通过 Binder 触发双进程热更新。
 */
object PrefReader {

    @Volatile
    var systemPrefs: PrefsSnapshot = PrefsSnapshot()
        private set

    @Volatile
    var powerKeeperPrefs: PrefsSnapshot = PrefsSnapshot()
        private set

    @Volatile
    private var isSystemProcessActive: Boolean = false

    @Volatile
    private var isPowerKeeperProcessActive: Boolean = false

    /** system_server 侧配置分组热更新后的联动回调（由 SystemHooks 注册） */
    @Volatile
    var onSystemGroupReloaded: ((key: String, group: PrefGroup, snapshot: PrefsSnapshot) -> Unit)? = null

    /** powerkeeper 侧配置分组热更新后的联动回调（由 PowerKeeperHooks 注册） */
    @Volatile
    var onPowerKeeperGroupReloaded: ((key: String, group: PrefGroup, snapshot: PrefsSnapshot) -> Unit)? = null

    /**
     * 获取当前进程生效的配置快照（供 Logger 等公共组件读取）。
     */
    fun activePrefs(): PrefsSnapshot {
        return when {
            isSystemProcessActive -> systemPrefs
            isPowerKeeperProcessActive -> powerKeeperPrefs
            else -> systemPrefs
        }
    }

    /**
     * 初始化 system_server 进程配置快照。
     */
    fun initSystemPrefs(modernModule: XposedModule? = null): PrefsSnapshot {
        isSystemProcessActive = true
        if (modernModule != null) {
            ModernBridge.xposedModule = modernModule
        }
        val rawMap = readRawMapFromRemotePrefs() ?: return systemPrefs
        systemPrefs = buildFullSnapshot(rawMap)
        return systemPrefs
    }

    /**
     * 初始化 com.miui.powerkeeper 进程配置快照。
     */
    fun initPowerKeeperPrefs(modernModule: XposedModule? = null): PrefsSnapshot {
        isPowerKeeperProcessActive = true
        if (modernModule != null) {
            ModernBridge.xposedModule = modernModule
        }
        val rawMap = readRawMapFromRemotePrefs() ?: return powerKeeperPrefs
        powerKeeperPrefs = buildFullSnapshot(rawMap)
        return powerKeeperPrefs
    }

    /**
     * 按变更的键名增量重载 system_server 侧配置。
     */
    fun reloadSystemByKey(changedKey: String) {
        val rawMap = readRawMapFromRemotePrefs() ?: return
        val prev = systemPrefs
        val group = PrefGroup.fromKey(changedKey)
        val updated = if (group == null) {
            buildFullSnapshot(rawMap)
        } else {
            mergeGroupIntoSnapshot(prev, rawMap, group)
        }
        systemPrefs = updated
        val tag = if (group == null) "整表[$changedKey]" else changedKey
        logSnapshot("system_server", tag, updated)

        if (group == null || group == PrefGroup.MILLET_FLAGS) {
            if (!updated.simplifyLog &&
                (changedKey == PrefsKeys.SIMPLIFY_LOG || prev.simplifyLog)
            ) {
                dispatchSystemReloadCallback(PrefsKeys.SIMPLIFY_LOG, PrefGroup.MILLET_FLAGS, updated)
            }
            if (changedKey == PrefsKeys.IS_LOG ||
                prev.isLog != updated.isLog
            ) {
                dispatchSystemReloadCallback(PrefsKeys.IS_LOG, PrefGroup.MILLET_FLAGS, updated)
            }
            if (changedKey == PrefsKeys.BATTERY_CHANGE_OBSERVE ||
                prev.batteryChangeObserve != updated.batteryChangeObserve
            ) {
                dispatchSystemReloadCallback(PrefsKeys.BATTERY_CHANGE_OBSERVE, PrefGroup.MILLET_FLAGS, updated)
            }
            if (changedKey == PrefsKeys.FREEZE_STATS ||
                prev.freezeStats != updated.freezeStats
            ) {
                dispatchSystemReloadCallback(PrefsKeys.FREEZE_STATS, PrefGroup.MILLET_FLAGS, updated)
            }
            if (changedKey == PrefsKeys.FREEZE_DUAL_APP ||
                prev.freezeDualApp != updated.freezeDualApp
            ) {
                dispatchSystemReloadCallback(PrefsKeys.FREEZE_DUAL_APP, PrefGroup.MILLET_FLAGS, updated)
            }
            if (changedKey == PrefsKeys.BLOCK_CLOUD_FROZEN_WHITELIST ||
                prev.blockCloudFrozenWhitelist != updated.blockCloudFrozenWhitelist
            ) {
                dispatchSystemReloadCallback(
                    PrefsKeys.BLOCK_CLOUD_FROZEN_WHITELIST,
                    PrefGroup.MILLET_FLAGS,
                    updated
                )
            }
        }
        if (group == null || group == PrefGroup.DOZE_FLAGS) {
            if (changedKey == PrefsKeys.ENABLE_DOZE || prev.enableDoze != updated.enableDoze) {
                dispatchSystemReloadCallback(PrefsKeys.ENABLE_DOZE, PrefGroup.DOZE_FLAGS, updated)
            }
        }
        if (group == null || group == PrefGroup.APP_SETS) {
            if (changedKey == PrefsKeys.MESSAGE_APP_SET || prev.messageAppSet != updated.messageAppSet) {
                dispatchSystemReloadCallback(PrefsKeys.MESSAGE_APP_SET, PrefGroup.APP_SETS, updated)
            }
            if (changedKey == PrefsKeys.WHITE_APP_SET || prev.whiteAppSet != updated.whiteAppSet) {
                dispatchSystemReloadCallback(PrefsKeys.WHITE_APP_SET, PrefGroup.APP_SETS, updated)
            }
            if (changedKey == PrefsKeys.FORCE_FREEZE_SET || prev.forceFreezeSet != updated.forceFreezeSet) {
                dispatchSystemReloadCallback(PrefsKeys.FORCE_FREEZE_SET, PrefGroup.APP_SETS, updated)
            }
        }
    }

    private fun dispatchSystemReloadCallback(key: String, group: PrefGroup, snapshot: PrefsSnapshot) {
        try {
            onSystemGroupReloaded?.invoke(key, group, snapshot)
        } catch (t: Throwable) {
            Logger.error("onSystemGroupReloaded [$key] failed", t)
        }
    }

    /**
     * 按变更的键名增量重载 powerkeeper 侧配置。
     */
    fun reloadPowerKeeperByKey(changedKey: String) {
        val rawMap = readRawMapFromRemotePrefs() ?: return
        val prev = powerKeeperPrefs
        val group = PrefGroup.fromKey(changedKey)
        val updated = if (group == null) {
            buildFullSnapshot(rawMap)
        } else {
            mergeGroupIntoSnapshot(prev, rawMap, group)
        }
        powerKeeperPrefs = updated
        val tag = if (group == null) "整表[$changedKey]" else changedKey
        logSnapshot("powerkeeper", tag, updated)

        val appSetsChanged = prev.whiteAppSet != updated.whiteAppSet ||
            prev.messageAppSet != updated.messageAppSet ||
            prev.forceFreezeSet != updated.forceFreezeSet
        if (group == PrefGroup.APP_SETS || (group == null && appSetsChanged)) {
            try {
                onPowerKeeperGroupReloaded?.invoke(changedKey, PrefGroup.APP_SETS, updated)
            } catch (t: Throwable) {
                Logger.error("onPowerKeeperGroupReloaded [$changedKey] failed", t)
            }
        }
        val dualAppChanged = prev.freezeDualApp != updated.freezeDualApp
        if ((group == PrefGroup.MILLET_FLAGS && changedKey == PrefsKeys.FREEZE_DUAL_APP) ||
            (group == null && dualAppChanged)
        ) {
            try {
                onPowerKeeperGroupReloaded?.invoke(PrefsKeys.FREEZE_DUAL_APP, PrefGroup.MILLET_FLAGS, updated)
            } catch (t: Throwable) {
                Logger.error("onPowerKeeperGroupReloaded [${PrefsKeys.FREEZE_DUAL_APP}] failed", t)
            }
        }
        val cloudFrozenChanged = prev.blockCloudFrozenWhitelist != updated.blockCloudFrozenWhitelist
        if ((group == PrefGroup.MILLET_FLAGS && changedKey == PrefsKeys.BLOCK_CLOUD_FROZEN_WHITELIST) ||
            (group == null && cloudFrozenChanged)
        ) {
            try {
                onPowerKeeperGroupReloaded?.invoke(
                    PrefsKeys.BLOCK_CLOUD_FROZEN_WHITELIST,
                    PrefGroup.MILLET_FLAGS,
                    updated
                )
            } catch (t: Throwable) {
                Logger.error(
                    "onPowerKeeperGroupReloaded [${PrefsKeys.BLOCK_CLOUD_FROZEN_WHITELIST}] failed",
                    t
                )
            }
        }
    }

    /**
     * 打印一份快照的关键开关与名单大小。
     *
     * 必须用 `alwaysWarn`：`Logger.info` 受 `isLog` 门控，而这里恰恰是要观测 `isLog` 本身。
     */
    private fun logSnapshot(scene: String, tag: String, snapshot: PrefsSnapshot) {
        Logger.alwaysWarn(
            "配置快照[$scene][$tag]: isLog=${snapshot.isLog}, simplifyLog=${snapshot.simplifyLog}, " +
                "disableKill=${snapshot.disableKill}, disableCharge=${snapshot.disableCharge}, " +
                "disableScreenOnThaw=${snapshot.disableScreenOnThaw}, " +
                "blockCloudDoze=${snapshot.blockCloudDozeWhitelist}, " +
                "blockCloudFrozen=${snapshot.blockCloudFrozenWhitelist}, " +
                "freezeStats=${snapshot.freezeStats}, freezeDualApp=${snapshot.freezeDualApp}, " +
                "enableDoze=${snapshot.enableDoze}, " +
                "whiteAppSet=${snapshot.whiteAppSet.size}, " +
                "messageAppSet=${snapshot.messageAppSet.size}, ignoreNetworkWake=${snapshot.ignoreNetworkWakeSet.size}"
        )
    }

    /**
     * 读取完整键值表（通过 Modern LibXposed getRemotePreferences 读取）。
     */
    private fun readRawMapFromRemotePrefs(): Map<String, Any?>? {
        val modernModule = ModernBridge.xposedModule ?: run {
            Logger.alwaysWarn("配置读取未确认：框架未注入，保留现有快照")
            return null
        }
        return try {
            val all = modernModule.getRemotePreferences(PrefsKeys.PREFS_FILE_NAME).all
                ?: return null.also { Logger.alwaysWarn("配置读取未确认：远程配置不可用，保留现有快照") }
            HashMap(all)
        } catch (t: Throwable) {
            Logger.error("远程配置读取失败，保留现有快照", t)
            null
        }
    }

    private fun buildFullSnapshot(raw: Map<String, Any?>): PrefsSnapshot {
        var snapshot = PrefsSnapshot()
        for (group in PrefGroup.entries) {
            snapshot = mergeGroupIntoSnapshot(snapshot, raw, group)
        }
        return PrefsSchema.sanitizeSnapshot(snapshot).snapshot
    }

    private fun mergeGroupIntoSnapshot(
        base: PrefsSnapshot,
        raw: Map<String, Any?>,
        group: PrefGroup
    ): PrefsSnapshot {
        return when (group) {
            PrefGroup.MILLET_FLAGS -> base.copy(
                isLog = getBooleanOrDefault(raw, PrefsKeys.IS_LOG),
                simplifyLog = getBooleanOrDefault(raw, PrefsKeys.SIMPLIFY_LOG),
                disableKill = getBooleanOrDefault(raw, PrefsKeys.DISABLE_KILL),
                disableCharge = getBooleanOrDefault(raw, PrefsKeys.DISABLE_CHARGE),
                disableScreenOnThaw = getBooleanOrDefault(raw, PrefsKeys.DISABLE_SCREEN_ON_THAW),
                blockAddPowerSaveWhitelistApps = getBooleanOrDefault(
                    raw,
                    PrefsKeys.BLOCK_ADD_POWER_SAVE_WHITELIST_APPS
                ),
                blockCloudDozeWhitelist = getBooleanOrDefault(
                    raw,
                    PrefsKeys.BLOCK_CLOUD_DOZE_WHITELIST
                ),
                blockCloudFrozenWhitelist = getBooleanOrDefault(
                    raw,
                    PrefsKeys.BLOCK_CLOUD_FROZEN_WHITELIST
                ),
                batteryChangeObserve = getBooleanOrDefault(raw, PrefsKeys.BATTERY_CHANGE_OBSERVE),
                logToLocal = getBooleanOrDefault(raw, PrefsKeys.LOG_TO_LOCAL),
                freezeStats = getBooleanOrDefault(raw, PrefsKeys.FREEZE_STATS),
                freezeDualApp = getBooleanOrDefault(raw, PrefsKeys.FREEZE_DUAL_APP)
            )

            PrefGroup.DOZE_FLAGS -> base.copy(
                dozeLog = getBooleanOrDefault(raw, PrefsKeys.DOZE_LOG),
                enableDoze = getBooleanOrDefault(raw, PrefsKeys.ENABLE_DOZE),
                skipStateCheck = getBooleanOrDefault(raw, PrefsKeys.SKIP_STATE_CHECK),
                disableMotionListener = getBooleanOrDefault(raw, PrefsKeys.DISABLE_MOTION_LISTENER)
            )

            PrefGroup.APP_SETS -> PrefsSchema.sanitizeSnapshot(
                base.copy(
                    whiteAppSet = getStringSetOrDefault(raw, PrefsKeys.WHITE_APP_SET),
                    messageAppSet = getStringSetOrDefault(raw, PrefsKeys.MESSAGE_APP_SET),
                    forceFreezeSet = getStringSetOrDefault(raw, PrefsKeys.FORCE_FREEZE_SET),
                    blockThawSet = getStringSetOrDefault(raw, PrefsKeys.BLOCK_THAW_SET),
                    forbidSyncBinderSet = getStringSetOrDefault(raw, PrefsKeys.FORBID_SYNC_BINDER_SET),
                    notFreezeWhenHasFgsSet = getStringSetOrDefault(raw, PrefsKeys.NOT_FREEZE_WHEN_HAS_FGS_SET),
                    ignoreNetworkWakeSet = getStringSetOrDefault(raw, PrefsKeys.IGNORE_NETWORK_WAKE_SET),
                )
            ).snapshot

            PrefGroup.LOG_CONFIG -> base.copy(
                logType = PrefsSchema.normalizeLogType(raw[PrefsKeys.LOG_TYPE] as? String),
                logLevel = PrefsSchema.normalizeLogLevel(raw[PrefsKeys.LOG_LEVEL] as? String),
            )
        }
    }

    private fun getBooleanOrDefault(raw: Map<String, Any?>, key: String): Boolean {
        return (raw[key] as? Boolean) ?: PrefsDefaults.booleanDefault(key)
    }

    private fun getStringSetOrDefault(raw: Map<String, Any?>, key: String): Set<String> {
        val value = raw[key]
        if (value is Collection<*>) {
            val result = LinkedHashSet<String>()
            for (item in value) {
                if (item is String && AppUidUtils.validPolicyTarget(item)) {
                    result.add(item)
                }
            }
            return result
        }
        return PrefsDefaults.stringSetDefault(key)
    }

    // ==================== UI 侧读写与热更新封装 ====================

    private val migratedUiPrefs = java.util.Collections.newSetFromMap(
        java.util.IdentityHashMap<SharedPreferences, Boolean>()
    )

    /**
     * 打开 Device-Protected Storage 下的 Settings.xml（供 App UI 进程调用）。
     * Direct Boot 阶段即可访问；跨进程配置由 [XposedPrefsBridge] 同步到 LibXposed RemotePreferences，
     * 不再使用已废弃的 MODE_WORLD_READABLE / MODE_MULTI_PROCESS。
     */
    fun openUiPrefs(context: Context): SharedPreferences {
        val deContext = try {
            context.createDeviceProtectedStorageContext() ?: context
        } catch (_: Throwable) {
            context
        }
        val prefs = deContext.getSharedPreferences(PrefsKeys.PREFS_FILE_NAME, Context.MODE_PRIVATE)
        ensureUiPrefsSchema(prefs)
        return prefs
    }

    internal fun ensureUiPrefsSchema(prefs: SharedPreferences) {
        synchronized(migratedUiPrefs) {
            if (!migratedUiPrefs.add(prefs)) return
        }
        val staleKeys = listOf("fastScreenOffFreeze", "launchModePolicy", "dozeEnterDelaySec")
            .filter { prefs.contains(it) }
        val version = runCatching { prefs.getInt(PrefsSchema.KEY_SCHEMA_VERSION, 0) }.getOrDefault(0)
        if (version >= PrefsSchema.CURRENT_VERSION && staleKeys.isEmpty()) return
        val raw = readRawUiSnapshot(prefs)
        val result = PrefsSchema.sanitizeSnapshot(raw)
        applyPrefsSuppressed(prefs, clearFirst = false) {
            for (stale in staleKeys) remove(stale)
            if (version < PrefsSchema.CURRENT_VERSION) {
                putInt(PrefsSchema.KEY_SCHEMA_VERSION, PrefsSchema.CURRENT_VERSION)
            }
            if (result.modified) {
                val cleaned = result.snapshot
                putStringSet(PrefsKeys.WHITE_APP_SET, cleaned.whiteAppSet)
                putStringSet(PrefsKeys.MESSAGE_APP_SET, cleaned.messageAppSet)
                putStringSet(PrefsKeys.FORCE_FREEZE_SET, cleaned.forceFreezeSet)
                putStringSet(PrefsKeys.BLOCK_THAW_SET, cleaned.blockThawSet)
                putStringSet(PrefsKeys.FORBID_SYNC_BINDER_SET, cleaned.forbidSyncBinderSet)
                putStringSet(PrefsKeys.NOT_FREEZE_WHEN_HAS_FGS_SET, cleaned.notFreezeWhenHasFgsSet)
                putStringSet(PrefsKeys.IGNORE_NETWORK_WAKE_SET, cleaned.ignoreNetworkWakeSet)
                putString(PrefsKeys.LOG_TYPE, cleaned.logType)
                putString(PrefsKeys.LOG_LEVEL, cleaned.logLevel)
            }
        }
    }

    /**
     * UI 侧读取布尔开关（与 Hook 侧共享同一默认值表）。
     */
    fun readUiBoolean(prefs: SharedPreferences, key: String): Boolean {
        return prefs.getBoolean(key, PrefsDefaults.booleanDefault(key))
    }

    /**
     * UI 侧读取应用包名集合（返回可变 LinkedHashSet 副本，与 Hook 侧共享同一默认值表）。
     */
    fun readUiStringSet(prefs: SharedPreferences, key: String): LinkedHashSet<String> {
        val defaultSet = PrefsDefaults.stringSetDefault(key)
        val stored = prefs.getStringSet(key, defaultSet) ?: defaultSet
        val copy = LinkedHashSet<String>()
        for (item in stored) {
            if (AppUidUtils.validPolicyTarget(item)) {
                copy.add(item)
            }
        }
        return copy
    }

    /**
     * 校验策略目标并复制集合，保持本地与远程配置的编码一致。
     */
    fun sanitizeStringSet(values: Set<String>): LinkedHashSet<String> {
        val cleanCopy = LinkedHashSet<String>()
        for (target in values) {
            require(AppUidUtils.validPolicyTarget(target)) { "Invalid policy target: $target" }
            cleanCopy.add(target)
        }
        return cleanCopy
    }

    /** UI 侧通过 [PrefReader] 完成一次本地落盘后的轻量监听（仅 App 进程内使用）。 */
    @Volatile
    var onUiPrefsMutated: ((SharedPreferences) -> Unit)? = null

    /**
     * 读取 UI 侧完整配置快照（与 Hook 侧 [buildFullSnapshot] 共享同一默认值表与 Schema 净化）。
     */
    fun readUiSnapshot(prefs: SharedPreferences): PrefsSnapshot =
        PrefsSchema.sanitizeSnapshot(readRawUiSnapshot(prefs)).snapshot

    private fun readRawUiSnapshot(prefs: SharedPreferences): PrefsSnapshot = PrefsSnapshot(
        isLog = readUiBoolean(prefs, PrefsKeys.IS_LOG),
        simplifyLog = readUiBoolean(prefs, PrefsKeys.SIMPLIFY_LOG),
        disableKill = readUiBoolean(prefs, PrefsKeys.DISABLE_KILL),
        disableCharge = readUiBoolean(prefs, PrefsKeys.DISABLE_CHARGE),
        disableScreenOnThaw = readUiBoolean(prefs, PrefsKeys.DISABLE_SCREEN_ON_THAW),
        blockAddPowerSaveWhitelistApps = readUiBoolean(prefs, PrefsKeys.BLOCK_ADD_POWER_SAVE_WHITELIST_APPS),
        blockCloudDozeWhitelist = readUiBoolean(prefs, PrefsKeys.BLOCK_CLOUD_DOZE_WHITELIST),
        blockCloudFrozenWhitelist = readUiBoolean(prefs, PrefsKeys.BLOCK_CLOUD_FROZEN_WHITELIST),
        batteryChangeObserve = readUiBoolean(prefs, PrefsKeys.BATTERY_CHANGE_OBSERVE),
        logToLocal = readUiBoolean(prefs, PrefsKeys.LOG_TO_LOCAL),
        freezeStats = readUiBoolean(prefs, PrefsKeys.FREEZE_STATS),
        freezeDualApp = readUiBoolean(prefs, PrefsKeys.FREEZE_DUAL_APP),
        dozeLog = readUiBoolean(prefs, PrefsKeys.DOZE_LOG),
        enableDoze = readUiBoolean(prefs, PrefsKeys.ENABLE_DOZE),
        skipStateCheck = readUiBoolean(prefs, PrefsKeys.SKIP_STATE_CHECK),
        disableMotionListener = readUiBoolean(prefs, PrefsKeys.DISABLE_MOTION_LISTENER),
        whiteAppSet = readUiStringSet(prefs, PrefsKeys.WHITE_APP_SET),
        messageAppSet = readUiStringSet(prefs, PrefsKeys.MESSAGE_APP_SET),
        forceFreezeSet = readUiStringSet(prefs, PrefsKeys.FORCE_FREEZE_SET),
        blockThawSet = readUiStringSet(prefs, PrefsKeys.BLOCK_THAW_SET),
        forbidSyncBinderSet = readUiStringSet(prefs, PrefsKeys.FORBID_SYNC_BINDER_SET),
        notFreezeWhenHasFgsSet = readUiStringSet(prefs, PrefsKeys.NOT_FREEZE_WHEN_HAS_FGS_SET),
        ignoreNetworkWakeSet = readUiStringSet(prefs, PrefsKeys.IGNORE_NETWORK_WAKE_SET),
        logType = prefs.getString(PrefsKeys.LOG_TYPE, PrefsDefaults.LOG_TYPE) ?: PrefsDefaults.LOG_TYPE,
        logLevel = prefs.getString(PrefsKeys.LOG_LEVEL, PrefsDefaults.LOG_LEVEL) ?: PrefsDefaults.LOG_LEVEL,
    )

    private fun applyPrefsSuppressed(
        prefs: SharedPreferences,
        clearFirst: Boolean = false,
        block: SharedPreferences.Editor.() -> Unit
    ) {
        XposedPrefsBridge.withLocalListenerSuppressed {
            val editor = prefs.edit()
            if (clearFirst) {
                editor.clear()
            }
            editor.block()
            editor.apply()
        }
        onUiPrefsMutated?.invoke(prefs)
    }

    private fun writeKeysAndNotify(
        prefs: SharedPreferences,
        keys: Set<String>,
        notifyKey: String,
        block: SharedPreferences.Editor.() -> Unit
    ) {
        applyPrefsSuppressed(prefs, block = block)
        XposedPrefsBridge.syncAndNotifyKeys(keys, notifyKey)
    }

    /**
     * UI 侧写入布尔配置，同步远程配置并触发一次跨进程热重载。
     */
    fun putBooleanAndNotify(prefs: SharedPreferences, key: String, value: Boolean) =
        writeKeysAndNotify(prefs, setOf(key), key) { putBoolean(key, value) }

    /**
     * UI 侧写入字符串配置，同步远程配置并触发一次跨进程热重载。
     */
    fun putStringAndNotify(prefs: SharedPreferences, key: String, value: String) =
        writeKeysAndNotify(prefs, setOf(key), key) { putString(key, value) }

    /**
     * UI 侧写入包名集合配置，同步远程配置并触发一次跨进程热重载。
     */
    fun putStringSetAndNotify(prefs: SharedPreferences, key: String, values: Set<String>) =
        writeKeysAndNotify(prefs, setOf(key), key) { putStringSet(key, sanitizeStringSet(values)) }

    /**
     * UI 侧在单次事务中批量更新多个应用集合，并只发一次远程同步与热重载通知。
     */
    fun putStringSetsAndNotify(
        prefs: SharedPreferences,
        updates: Map<String, Set<String>>,
        notifyKey: String = updates.keys.firstOrNull() ?: "*"
    ) {
        if (updates.isEmpty()) return
        writeKeysAndNotify(prefs, updates.keys, notifyKey) {
            for ((key, values) in updates) {
                putStringSet(key, sanitizeStringSet(values))
            }
        }
    }

    /**
     * UI 侧在单次事务中批量编辑配置（如 JSON 恢复 / 恢复默认），避免逐键反复触发推表。
     */
    fun editAllAndNotify(
        prefs: SharedPreferences,
        clearFirst: Boolean = false,
        block: SharedPreferences.Editor.() -> Unit
    ) {
        applyPrefsSuppressed(prefs, clearFirst = clearFirst, block = block)
        XposedPrefsBridge.syncAndNotifyAll()
    }
}
