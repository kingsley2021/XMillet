package io.github.univers629.millet.core

/**
 * Doze 状态机的实时快照：当前所处状态与生效中的阻塞约束。
 *
 * 与 [DozeDiagnostics] 分工：前者记录最近一次深睡尝试结果，本类记录当前状态机字段，
 * 供环境检查页展示实时状态与阻塞原因。
 */
data class DozeLiveState(
    val state: Int,
    val forced: Boolean,
    val charging: Boolean,
    val screenOn: Boolean,
    val blockingConstraints: Int,
    val blockerNames: List<String>,
) {
    /** 是否已进入深度睡眠（IDLE 或 IDLE_MAINTENANCE）。 */
    val isDeepIdle: Boolean get() = state == STATE_IDLE || state == STATE_IDLE_MAINTENANCE

    /** 处于浅睡阶段（IDLE_PENDING / SENSING / LOCATING）。 */
    val isIdlePending: Boolean get() = state in STATE_IDLE_PENDING..STATE_LOCATING

    /**
     * 状态名。数值取自 `DeviceIdleController` 的 STATE_* 常量（HyperOS 1–4 一致）。
     */
    val stateName: String
        get() = when (state) {
            STATE_ACTIVE -> "ACTIVE"
            STATE_INACTIVE -> "INACTIVE"
            STATE_IDLE_PENDING -> "IDLE_PENDING"
            STATE_SENSING -> "SENSING"
            STATE_LOCATING -> "LOCATING"
            STATE_IDLE -> "IDLE"
            STATE_IDLE_MAINTENANCE -> "IDLE_MAINTENANCE"
            STATE_QUICK_DOZE -> "QUICK_DOZE"
            else -> "STATE_$state"
        }

    /** 一行摘要，例如 `当前 INACTIVE · 阻塞 2（alarm、signal）`。 */
    fun summarySuffix(): String = buildString {
        append("当前 ").append(stateName)
        if (blockingConstraints > 0) {
            append(" · 阻塞 ").append(blockingConstraints)
            if (blockerNames.isNotEmpty()) append("（").append(blockerNames.joinToString("、")).append("）")
        }
    }

    fun encode(): String = listOf(
        state,
        if (forced) 1 else 0,
        if (charging) 1 else 0,
        if (screenOn) 1 else 0,
        blockingConstraints,
        blockerNames.joinToString(",").replace('|', '/')
    ).joinToString("|")

    companion object {
        const val STATE_ACTIVE = 0
        const val STATE_INACTIVE = 1
        const val STATE_IDLE_PENDING = 2
        const val STATE_SENSING = 3
        const val STATE_LOCATING = 4
        const val STATE_IDLE = 5
        const val STATE_IDLE_MAINTENANCE = 6
        const val STATE_QUICK_DOZE = 7

        private const val MAX_BLOCKER_NAME = 16

        fun decode(raw: String?): DozeLiveState? {
            if (raw.isNullOrBlank()) return null
            val parts = raw.split('|')
            if (parts.size < 6) return null
            return runCatching {
                DozeLiveState(
                    state = parts[0].toInt(),
                    forced = parts[1].toInt() == 1,
                    charging = parts[2].toInt() == 1,
                    screenOn = parts[3].toInt() == 1,
                    blockingConstraints = parts[4].toInt(),
                    blockerNames = parts.drop(5).joinToString("|")
                        .split(',')
                        .map { it.take(MAX_BLOCKER_NAME) }
                        .filter { it.isNotEmpty() }
                )
            }.getOrNull()
        }
    }
}
