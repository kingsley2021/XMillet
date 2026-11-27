package io.github.univers629.millet.core

/**
 * 20 个配置键名、UI/Hook 共用单一默认值表、4 组热更新分组枚举与配置快照模型。
 *
 * 修正缺陷 #6：
 * 原模块中 UI 侧与 Hook 侧各自硬编码默认值，导致 `disableKill`（UI=false / Hook=true）
 * 与 `messageAppSet`（UI={com.tencent.mm} / Hook=empty）两边不一致。
 * 本实现将全部 20 个键名与默认值收口于同一常量表，UI 与 Hook 引用同一真值。
 */
object PrefsKeys {
    const val MODULE_PACKAGE = "io.github.univers629.millet"
    const val PREFS_FILE_NAME = "Settings"

    // 1. 十一个 Boolean 开关
    const val IS_LOG = "isLog"
    const val SIMPLIFY_LOG = "simplifyLog"
    const val DISABLE_KILL = "disableKill"
    const val DISABLE_CHARGE = "disableCharge"

    /**
     * 亮屏时保持冻结：拦截 Greezer 的 `screen on` 解冻理由。
     *
     * 默认关闭——亮屏解冻是系统正常行为，开启后屏幕亮着时后台应用也会保持墓碑状态，
     * 前台拉起（`activity start` / `toFront`）等正常路径仍会唤醒。
     */
    const val DISABLE_SCREEN_ON_THAW = "disableScreenOnThaw"
    const val BLOCK_ADD_POWER_SAVE_WHITELIST_APPS = "blockAddPowerSaveWhitelistApps"

    /** 屏蔽 PowerKeeper 云端 Doze 白名单（`dozeWhiteListApps`）的读取与新下发。 */
    const val BLOCK_CLOUD_DOZE_WHITELIST = "blockCloudDozeWhitelist"

    /** 屏蔽官方云端墓碑白名单（`FrozenNewWhiteList`）；跨 OS1–OS4 统一生效。 */
    const val BLOCK_CLOUD_FROZEN_WHITELIST = "blockCloudFrozenWhitelist"
    const val BATTERY_CHANGE_OBSERVE = "batteryChangeObserve"
    const val LOG_TO_LOCAL = "logToLocal"
    const val DOZE_LOG = "dozeLog"
    const val ENABLE_DOZE = "enableDoze"
    const val SKIP_STATE_CHECK = "skipStateCheck"
    const val DISABLE_MOTION_LISTENER = "disableMotionListener"

    /**
     * 深睡被系统闹钟窗口拦住后，延迟约 11 分钟补试一次深睡。
     *
     * 默认关闭：补试会在闹钟窗口结束后再执行一次 `force-idle deep`，属于新增后台行为。
     */
    const val DOZE_RETRY_AFTER_ALARM = "dozeRetryAfterAlarm"
    const val FREEZE_STATS = "freezeStats"
    const val FREEZE_DUAL_APP = "freezeDualApp"

    // 2. 七个 Set<String> 策略目标集合：主用户包名或双开包名#999
    const val WHITE_APP_SET = "whiteAppSet"
    const val MESSAGE_APP_SET = "messageAppSet"
    const val FORCE_FREEZE_SET = "forceFreezeSet"
    const val BLOCK_THAW_SET = "blockThawSet"
    const val FORBID_SYNC_BINDER_SET = "forbidSyncBinderSet"
    const val NOT_FREEZE_WHEN_HAS_FGS_SET = "notFreezeWhenHasFgsSet"

    /**
     * 忽略网络唤醒：应用在名单内时，`PACKET` / `Net Change` / `Signal` 等网络类解冻理由
     * 不再命中放行表，冻结会一直保持到用户主动打开应用。
     */
    const val IGNORE_NETWORK_WAKE_SET = "ignoreNetworkWakeSet"

    // 3. 两个 String 日志配置项
    const val LOG_TYPE = "logType"
    const val LOG_LEVEL = "logLevel"

    // 4. UI 首启引导辅助键（非 22 核心键，备份/重置时一并管理）
    const val IS_FIRST = "isFirst"

    /** 核心键全集（备份 / 重置 / 迁移共用） */
    val ALL_KEYS: Set<String> = linkedSetOf(
        IS_LOG,
        SIMPLIFY_LOG,
        DISABLE_KILL,
        DISABLE_CHARGE,
        DISABLE_SCREEN_ON_THAW,
        BLOCK_ADD_POWER_SAVE_WHITELIST_APPS,
        BLOCK_CLOUD_DOZE_WHITELIST,
        BLOCK_CLOUD_FROZEN_WHITELIST,
        BATTERY_CHANGE_OBSERVE,
        LOG_TO_LOCAL,
        DOZE_LOG,
        ENABLE_DOZE,
        SKIP_STATE_CHECK,
        DISABLE_MOTION_LISTENER,
        DOZE_RETRY_AFTER_ALARM,
        FREEZE_STATS,
        FREEZE_DUAL_APP,
        WHITE_APP_SET,
        MESSAGE_APP_SET,
        FORCE_FREEZE_SET,
        BLOCK_THAW_SET,
        FORBID_SYNC_BINDER_SET,
        NOT_FREEZE_WHEN_HAS_FGS_SET,
        IGNORE_NETWORK_WAKE_SET,
        LOG_TYPE,
        LOG_LEVEL
    )
}

/**
 * UI 与 Hook 共用的唯一默认值常量表（修正缺陷 #6）。
 */
object PrefsDefaults {
    // Boolean 默认值
    const val IS_LOG = false
    const val SIMPLIFY_LOG = false
    const val DISABLE_KILL = true
    const val DISABLE_CHARGE = true
    const val DISABLE_SCREEN_ON_THAW = true
    const val BLOCK_ADD_POWER_SAVE_WHITELIST_APPS = true
    const val BLOCK_CLOUD_DOZE_WHITELIST = false
    const val BLOCK_CLOUD_FROZEN_WHITELIST = true
    const val BATTERY_CHANGE_OBSERVE = false
    const val LOG_TO_LOCAL = false
    const val DOZE_LOG = false
    const val ENABLE_DOZE = true
    const val SKIP_STATE_CHECK = true
    const val DISABLE_MOTION_LISTENER = true
    const val DOZE_RETRY_AFTER_ALARM = false
    const val FREEZE_STATS = true
    const val FREEZE_DUAL_APP = true

    // Set<String> 默认值（保持 LinkedHashSet 顺序语义）
    val WHITE_APP_SET: Set<String> = emptySet()
    val MESSAGE_APP_SET: Set<String> = linkedSetOf("com.tencent.mm")
    val FORCE_FREEZE_SET: Set<String> = emptySet()
    val BLOCK_THAW_SET: Set<String> = emptySet()
    val FORBID_SYNC_BINDER_SET: Set<String> = emptySet()
    val NOT_FREEZE_WHEN_HAS_FGS_SET: Set<String> = emptySet()
    val IGNORE_NETWORK_WAKE_SET: Set<String> = emptySet()

    // String 默认值
    const val LOG_TYPE = "xposed"
    const val LOG_LEVEL = "debug"

    /**
     * 按键名查询布尔默认值。
     */
    fun booleanDefault(key: String): Boolean = when (key) {
        PrefsKeys.IS_LOG -> IS_LOG
        PrefsKeys.SIMPLIFY_LOG -> SIMPLIFY_LOG
        PrefsKeys.DISABLE_KILL -> DISABLE_KILL
        PrefsKeys.DISABLE_CHARGE -> DISABLE_CHARGE
        PrefsKeys.DISABLE_SCREEN_ON_THAW -> DISABLE_SCREEN_ON_THAW
        PrefsKeys.BLOCK_ADD_POWER_SAVE_WHITELIST_APPS -> BLOCK_ADD_POWER_SAVE_WHITELIST_APPS
        PrefsKeys.BLOCK_CLOUD_DOZE_WHITELIST -> BLOCK_CLOUD_DOZE_WHITELIST
        PrefsKeys.BLOCK_CLOUD_FROZEN_WHITELIST -> BLOCK_CLOUD_FROZEN_WHITELIST
        PrefsKeys.BATTERY_CHANGE_OBSERVE -> BATTERY_CHANGE_OBSERVE
        PrefsKeys.LOG_TO_LOCAL -> LOG_TO_LOCAL
        PrefsKeys.DOZE_LOG -> DOZE_LOG
        PrefsKeys.ENABLE_DOZE -> ENABLE_DOZE
        PrefsKeys.SKIP_STATE_CHECK -> SKIP_STATE_CHECK
        PrefsKeys.DISABLE_MOTION_LISTENER -> DISABLE_MOTION_LISTENER
        PrefsKeys.DOZE_RETRY_AFTER_ALARM -> DOZE_RETRY_AFTER_ALARM
        PrefsKeys.FREEZE_STATS -> FREEZE_STATS
        PrefsKeys.FREEZE_DUAL_APP -> FREEZE_DUAL_APP
        else -> false
    }

    /**
     * 按键名查询字符串集合默认值（返回防御性拷贝）。
     */
    fun stringSetDefault(key: String): LinkedHashSet<String> = when (key) {
        PrefsKeys.MESSAGE_APP_SET -> LinkedHashSet(MESSAGE_APP_SET)
        else -> LinkedHashSet()
    }
}

/**
 * 配置热更新分组枚举（对应原模块 RunningState 的 5 组枚举）。
 * 当 UI 修改某个键并发送跨进程通知时，目标进程仅重载受影响的那一组键。
 */
enum class PrefGroup(val keys: Set<String>) {
    MILLET_FLAGS(
        setOf(
            PrefsKeys.IS_LOG,
            PrefsKeys.SIMPLIFY_LOG,
            PrefsKeys.DISABLE_KILL,
            PrefsKeys.DISABLE_CHARGE,
            PrefsKeys.DISABLE_SCREEN_ON_THAW,
            PrefsKeys.BLOCK_ADD_POWER_SAVE_WHITELIST_APPS,
            PrefsKeys.BLOCK_CLOUD_DOZE_WHITELIST,
            PrefsKeys.BLOCK_CLOUD_FROZEN_WHITELIST,
            PrefsKeys.BATTERY_CHANGE_OBSERVE,
            PrefsKeys.LOG_TO_LOCAL,
            PrefsKeys.FREEZE_STATS,
            PrefsKeys.FREEZE_DUAL_APP
        )
    ),
    DOZE_FLAGS(
        setOf(
            PrefsKeys.DOZE_LOG,
            PrefsKeys.ENABLE_DOZE,
            PrefsKeys.SKIP_STATE_CHECK,
            PrefsKeys.DISABLE_MOTION_LISTENER,
            PrefsKeys.DOZE_RETRY_AFTER_ALARM
        )
    ),
    APP_SETS(
        setOf(
            PrefsKeys.WHITE_APP_SET,
            PrefsKeys.MESSAGE_APP_SET,
            PrefsKeys.FORCE_FREEZE_SET,
            PrefsKeys.BLOCK_THAW_SET,
            PrefsKeys.FORBID_SYNC_BINDER_SET,
            PrefsKeys.NOT_FREEZE_WHEN_HAS_FGS_SET,
            PrefsKeys.IGNORE_NETWORK_WAKE_SET
        )
    ),
    LOG_CONFIG(
        setOf(
            PrefsKeys.LOG_LEVEL,
            PrefsKeys.LOG_TYPE
        )
    );

    companion object {
        fun fromKey(key: String): PrefGroup? {
            return entries.firstOrNull { key in it.keys }
        }
    }
}

/**
 * 不可变配置快照（对应原模块 FreezeWhitelist / MemData）。
 */
data class PrefsSnapshot(
    // 7 个 Millet 布尔开关
    val isLog: Boolean = PrefsDefaults.IS_LOG,
    val simplifyLog: Boolean = PrefsDefaults.SIMPLIFY_LOG,
    val disableKill: Boolean = PrefsDefaults.DISABLE_KILL,
    val disableCharge: Boolean = PrefsDefaults.DISABLE_CHARGE,
    val disableScreenOnThaw: Boolean = PrefsDefaults.DISABLE_SCREEN_ON_THAW,
    val blockAddPowerSaveWhitelistApps: Boolean = PrefsDefaults.BLOCK_ADD_POWER_SAVE_WHITELIST_APPS,
    val blockCloudDozeWhitelist: Boolean = PrefsDefaults.BLOCK_CLOUD_DOZE_WHITELIST,
    val blockCloudFrozenWhitelist: Boolean = PrefsDefaults.BLOCK_CLOUD_FROZEN_WHITELIST,
    val batteryChangeObserve: Boolean = PrefsDefaults.BATTERY_CHANGE_OBSERVE,
    val logToLocal: Boolean = PrefsDefaults.LOG_TO_LOCAL,
    val freezeStats: Boolean = PrefsDefaults.FREEZE_STATS,
    val freezeDualApp: Boolean = PrefsDefaults.FREEZE_DUAL_APP,

    // 5 个 Doze 布尔开关
    val dozeLog: Boolean = PrefsDefaults.DOZE_LOG,
    val enableDoze: Boolean = PrefsDefaults.ENABLE_DOZE,
    val skipStateCheck: Boolean = PrefsDefaults.SKIP_STATE_CHECK,
    val disableMotionListener: Boolean = PrefsDefaults.DISABLE_MOTION_LISTENER,
    val dozeRetryAfterAlarm: Boolean = PrefsDefaults.DOZE_RETRY_AFTER_ALARM,

    // 7 个应用策略集合；无后缀条目仅属于主用户
    val whiteAppSet: Set<String> = PrefsDefaults.WHITE_APP_SET,
    val messageAppSet: Set<String> = PrefsDefaults.MESSAGE_APP_SET,
    val forceFreezeSet: Set<String> = PrefsDefaults.FORCE_FREEZE_SET,
    val blockThawSet: Set<String> = PrefsDefaults.BLOCK_THAW_SET,
    val forbidSyncBinderSet: Set<String> = PrefsDefaults.FORBID_SYNC_BINDER_SET,
    val notFreezeWhenHasFgsSet: Set<String> = PrefsDefaults.NOT_FREEZE_WHEN_HAS_FGS_SET,
    val ignoreNetworkWakeSet: Set<String> = PrefsDefaults.IGNORE_NETWORK_WAKE_SET,

    // 2 个日志属性
    val logType: String = PrefsDefaults.LOG_TYPE,
    val logLevel: String = PrefsDefaults.LOG_LEVEL
)
