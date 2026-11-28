package io.github.univers629.millet.core

/**
 * 墓碑统计的时间窗口。
 */
enum class StatsWindow(val label: String) {
    TODAY("今天"),
    WEEK("近 7 天"),
    BOOT("开机以来"),
}

/**
 * 唤醒（解冻）原因归类。
 *
 * 归类逻辑与 [FreezeReasonClassifier] 及 `FreezeGateHooks` 严格保持一致。
 */
enum class WakeCategory(val label: String) {
    USER_OPEN("前台活动"),
    NETWORK("网络活动"),
    BINDER("Binder 通信"),
    BROADCAST("广播/通知"),
    SCREEN_ON("亮屏"),
    CHARGING("充电"),
    OTHER("系统其他"),
    MODULE_OPERATION("模块操作"),
    LAUNCH_MODE("启动模式");

    /** Compatibility property: excludes foreground activity, not proof of a human tap. */
    val isBackground: Boolean
        get() = this != USER_OPEN
}

enum class StatsEventOrigin { UNKNOWN, NATIVE, MODULE, LAUNCH_MODE, SNAPSHOT }

/**
 * XMillet 拦截解冻所命中的规则。
 */
enum class InterceptRule(val label: String) {
    BLOCK_THAW("强力压制"),
    IGNORE_NETWORK("忽略网络唤醒"),
    FORBID_SYNC_BINDER("禁止同步 Binder"),
    NO_CHARGE_THAW("禁止插电解冻"),
    NO_SCREEN_ON_THAW("禁止亮屏解冻"),
    HARD_RULE("广播硬规则"),
}

/**
 * 最近动态事件类型。
 */
enum class StatsEventKind(val label: String) {
    FREEZE("冻结"),
    THAW("唤醒"),
    INTERCEPT("已拦截"),
    PROTECT("保护免冻"),
    KILL_BLOCKED("阻止查杀"),
}

/**
 * 时段分布柱：今天 = 24 根小时柱；近 7 天 = 7 根日柱。
 */
data class StatsTimeBucket(
    val label: String,
    val screenOnWakes: Int,
    val screenOffWakes: Int,
) {
    val total: Int
        get() = screenOnWakes + screenOffWakes
}

/**
 * 单个应用的墓碑统计数据（纯模型，不绑定 Android PackageManager / Compose）。
 */
data class AppFreezeStatsData(
    val uid: Int,
    val packageName: String,
    val freezeCount: Int,
    val wakeByCategory: Map<WakeCategory, Int>,
    val interceptByRule: Map<InterceptRule, Int>,
    val frozenMillis: Long,
    val observedMillis: Long,
    val protectedCount: Int = 0,
    val killBlockedCount: Int = 0,
    val topRawReasons: List<Pair<String, Int>> = emptyList(),
    val jitterCount: Int = 0,
    val topJitterReason: String = "",
) {
    val backgroundWakeCount: Int
        get() = wakeByCategory.entries.sumOf { if (it.key.isBackground) it.value else 0 }

    val userOpenCount: Int
        get() = wakeByCategory[WakeCategory.USER_OPEN] ?: 0

    val interceptCount: Int
        get() = interceptByRule.values.sum()

    val frozenRatio: Float
        get() = if (observedMillis > 0L) (frozenMillis.toFloat() / observedMillis).coerceIn(0f, 1f) else 0f
}

/**
 * 最近动态环形缓冲区记录项。
 */
data class StatsRecentEventData(
    val timeMillis: Long,
    val uid: Int,
    val packageName: String,
    val kind: StatsEventKind,
    val category: WakeCategory? = null,
    val rule: InterceptRule? = null,
    val rawReason: String = "",
    val origin: StatsEventOrigin = StatsEventOrigin.UNKNOWN,
)

/**
 * 墓碑统计跨进程快照数据。
 */
data class FreezeStatsSnapshotData(
    val window: StatsWindow,
    val enabled: Boolean = true,
    val sinceMillis: Long,
    val generatedAtMillis: Long,
    val freezeCount: Int,
    val wakeByCategory: Map<WakeCategory, Int>,
    val screenOffWakeCount: Int,
    val screenOffMillis: Long,
    val interceptByRule: Map<InterceptRule, Int>,
    val protectedCount: Int,
    val killBlockedCount: Int,
    val frozenMillisTotal: Long,
    val buckets: List<StatsTimeBucket>,
    val apps: List<AppFreezeStatsData>,
    val recent: List<StatsRecentEventData>,
    val memoryBytes: Long = 0,
    val sourceNote: String? = null,
    val dozeSummary: DozeSummaryData = DozeSummaryData(),
) {
    val backgroundWakeCount: Int
        get() = wakeByCategory.entries.sumOf { if (it.key.isBackground) it.value else 0 }

    val interceptCount: Int
        get() = interceptByRule.values.sum()

    val isEmpty: Boolean
        get() = freezeCount == 0 &&
            wakeByCategory.values.sum() == 0 &&
            interceptCount == 0 &&
            protectedCount == 0 &&
            killBlockedCount == 0
}

/**
 * 解冻/唤醒理由规则判定与分类器（由 Hook 闸门与统计采集器共用，保证语义完全一致）。
 *
 * MIUI 14 / HyperOS 1–3 采用空格与驼峰字面量（如 `"Activity Start"`、`"Sync Binder"`），
 * HyperOS 4（Android 17 `GreezeReason`）全面切换为下划线 `snake_case`（如 `"activity_start"`、`"sync_binder"`）。
 * 所有判定统一先经 [normalizeReason] 归一化，保证跨代行为一致。
 */
object FreezeReasonClassifier {

    /**
     * 将解冻原因归一化为小写单空格形式：`_`、`:`、`-` 与空白统一折叠为单个空格。
     */
    internal fun normalizeReason(reason: String): String {
        val sb = StringBuilder(reason.length)
        var prevSpace = true
        for (ch in reason) {
            val mapped = when (ch) {
                '_', ':', '-', '\t', '\n', '\r' -> ' '
                else -> ch.lowercaseChar()
            }
            if (mapped == ' ') {
                if (!prevSpace) {
                    sb.append(' ')
                    prevSpace = true
                }
            } else {
                sb.append(mapped)
                prevSpace = false
            }
        }
        if (sb.isNotEmpty() && sb[sb.length - 1] == ' ') {
            sb.setLength(sb.length - 1)
        }
        return sb.toString()
    }

    private val ALLOWED_NORMALIZED_REASONS: Set<String> = setOf(
        "immobulus",
        "launch mode",
        "fg important",
        "bf",
        "same uid",
        "subprocess",
        "ime",
        "notifi",
        "pendingnotify",
        "pending notify",
        "cmd thuid",
        "cmd thpid",
        "shell thaw all",
    )

    private val NETWORK_NORMALIZED_REASONS: Set<String> = setOf(
        "packet",
        "net change",
        "network connect",
        "signal",
    )

    fun isSyncBinderThawReason(reason: String): Boolean {
        val norm = normalizeReason(reason)
        return norm.startsWith("sync binder") || norm.startsWith("check binder")
    }

    fun isAllowedThawReason(reason: String): Boolean {
        val norm = normalizeReason(reason)
        if (norm.isEmpty()) return false
        return isUserOpenReasonNormalized(norm) ||
            isSyncBinderNormalized(norm) ||
            isNetworkWakeNormalized(norm) ||
            norm in ALLOWED_NORMALIZED_REASONS ||
            norm.startsWith("broadcast") ||
            norm.startsWith("bc action") ||
            norm.startsWith("receiver") ||
            norm.contains("mediabutton") ||
            norm.contains("media button")
    }

    fun isToFrontReason(reason: String): Boolean =
        isToFrontNormalized(normalizeReason(reason))

    fun isNetworkWakeReason(reason: String): Boolean =
        isNetworkWakeNormalized(normalizeReason(reason))

    fun isChargeThawReason(reason: String): Boolean {
        val norm = normalizeReason(reason)
        return norm.contains("charg") || norm.contains("充电")
    }

    fun isScreenOnThawReason(reason: String): Boolean =
        normalizeReason(reason).startsWith("screen on")

    fun isUserOpenReason(reason: String): Boolean =
        isUserOpenReasonNormalized(normalizeReason(reason))

    fun isBroadcastReason(reason: String): Boolean =
        isBroadcastNormalized(normalizeReason(reason))

    fun classifyWakeCategory(reason: String): WakeCategory {
        val norm = normalizeReason(reason)
        if (norm.isEmpty()) return WakeCategory.OTHER
        return when {
            norm == "launch mode" -> WakeCategory.LAUNCH_MODE
            isUserOpenReasonNormalized(norm) -> WakeCategory.USER_OPEN
            norm.contains("charg") || norm.contains("充电") -> WakeCategory.CHARGING
            norm.startsWith("screen on") -> WakeCategory.SCREEN_ON
            isSyncBinderNormalized(norm) ||
                norm == "fg important" ||
                norm == "bf" ||
                norm == "same uid" ||
                norm.contains("binder") -> WakeCategory.BINDER
            isBroadcastNormalized(norm) -> WakeCategory.BROADCAST
            isNetworkWakeNormalized(norm) -> WakeCategory.NETWORK
            else -> WakeCategory.OTHER
        }
    }

    private fun isSyncBinderNormalized(norm: String): Boolean =
        norm.startsWith("sync binder") || norm.startsWith("check binder")

    private fun isToFrontNormalized(norm: String): Boolean =
        norm.contains("tofront") ||
            norm.startsWith("activity front") ||
            norm.startsWith("activity start") ||
            norm == "activity resume" ||
            norm == "fg activity" ||
            norm == "multitask"

    private fun isUserOpenReasonNormalized(norm: String): Boolean =
        isToFrontNormalized(norm) ||
            norm == "freeformmode" ||
            norm == "freeform mode" ||
            norm == "smallw" ||
            norm == "small win" ||
            norm == "focus" ||
            norm == "ui bar" ||
            norm == "process start" ||
            norm == "display on" ||
            norm == "virtualdisplay" ||
            norm == "virtual display" ||
            norm == "carwith cast" ||
            norm == "app function" ||
            norm == "keycode"

    private fun isNetworkWakeNormalized(norm: String): Boolean =
        norm in NETWORK_NORMALIZED_REASONS || norm.startsWith("signal ")

    private fun isBroadcastNormalized(norm: String): Boolean =
        norm == "broadcast" ||
            norm == "broadcast cl" ||
            norm == "bc action" ||
            norm == "notifi" ||
            norm == "pendingnotify" ||
            norm == "pending notify" ||
            norm == "mipush" ||
            norm.startsWith("receiver") ||
            norm.contains("notify") ||
            norm.contains("notification") ||
            norm.contains("broadcast")
}
